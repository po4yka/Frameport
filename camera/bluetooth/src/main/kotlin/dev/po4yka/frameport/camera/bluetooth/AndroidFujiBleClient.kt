package dev.po4yka.frameport.camera.bluetooth

import dev.po4yka.frameport.camera.api.BleCameraAdvertisement
import dev.po4yka.frameport.camera.api.BleCameraRef
import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.CharacteristicId
import dev.po4yka.frameport.camera.api.FujiBleClient
import dev.po4yka.frameport.core.common.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Production implementation of [FujiBleClient].
 *
 * Architecture invariants (MUST NOT be violated):
 * - ALL [GattTransport] method calls happen from ONE coroutine: the [actorJob] coroutine.
 *   Concurrent GATT calls are forbidden. This is enforced structurally — only the actor
 *   loop dispatches to [gattTransport].
 * - Only ONE GATT operation is in flight at a time. The [operationQueue] channel (capacity 64)
 *   serializes all callers. The actor processes one [GattOperation] then moves to the next.
 * - EVERY GATT operation is wrapped in [withTimeout]. Connect uses [BleConstants.GATT_CONNECT_TIMEOUT_MS];
 *   characteristic read/write/notify use [BleConstants.GATT_OPERATION_TIMEOUT_MS].
 * - [disconnect] is idempotent. Calling it multiple times is safe.
 * - On disconnect or failure, all pending operations in the queue are cancelled with
 *   [BleOperationCancelled].
 * - Privacy: BLE MAC addresses and raw payloads are NEVER logged via Timber.
 *   Passphrases are NEVER logged at any level. See privacy-local-first.md.
 * - No java.util.concurrent.locks / java.util.concurrent.Mutex in any suspend path.
 *   Kotlinx.coroutines primitives only (Channel, MutableStateFlow).
 *
 * Reconnect strategy: bounded exponential backoff starting at [BleConstants.RECONNECT_BASE_DELAY_MS],
 * doubling each attempt, capped at 30 s. After [BleConstants.RECONNECT_MAX_ATTEMPTS] consecutive
 * failures, state transitions to [BleConnectionState.Failed] (terminal).
 */
@Singleton
class AndroidFujiBleClient
    @Inject
    internal constructor(
        private val bleScanner: BleScanner,
        private val gattTransport: GattTransport,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : FujiBleClient {
        // -------------------------------------------------------------------------
        // State
        // -------------------------------------------------------------------------

        private val _connectionState = MutableStateFlow(BleConnectionState.Disconnected)

        // cancel-safe: StateFlow; read-only projection.
        override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

        /**
         * Operation queue. Capacity 64 is generous for a BLE characteristic interaction pattern
         * (never more than a handful of ops queued in normal usage). Channel.RENDEZVOUS would
         * force callers to suspend until the actor is ready; Channel(64) lets callers enqueue
         * quickly and suspend only on the deferred result.
         */
        private val operationQueue = Channel<GattOperation>(capacity = 64)

        /** Scope that owns the actor and any scan-to-connect flows. */
        private val clientScope = CoroutineScope(ioDispatcher + SupervisorJob())

        /** Tracks the active actor coroutine so it can be cancelled on disconnect. */
        @Volatile
        private var actorJob: Job? = null

        /** Current target camera (set during [connect], cleared on [disconnect]). */
        @Volatile
        private var targetCamera: BleCameraRef? = null

        init {
            startActor()
        }

        // -------------------------------------------------------------------------
        // FujiBleClient — public API
        // -------------------------------------------------------------------------

        // cancel-safe: delegates to BleScanner.scan() which is a cold flow.
        override fun scan(): Flow<BleCameraAdvertisement> = bleScanner.scan()

        /**
         * Enqueue a connect operation for [camera] and await the result.
         *
         * cancel-safe: the deferred is completed by the actor. If the caller's coroutine is
         * cancelled before the actor processes the operation, the deferred is never resolved but
         * the coroutine completes with CancellationException normally.
         */
        override suspend fun connect(camera: BleCameraRef): Result<Unit> {
            if (_connectionState.value == BleConnectionState.Failed) {
                return Result.failure(
                    IllegalStateException("BLE client is in terminal Failed state. Create a new session."),
                )
            }
            targetCamera = camera
            _connectionState.value = BleConnectionState.Connecting

            val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
            return try {
                enqueueOperation(GattOperation.Connect(deferred))
                deferred.await()
                Result.success(Unit)
            } catch (e: BleOperationCancelled) {
                Result.failure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /**
         * Enqueue a characteristic read and await the result.
         *
         * cancel-safe: see [connect] rationale.
         * Privacy: characteristic UUID logged at debug level; payload is NEVER logged.
         */
        override suspend fun read(characteristic: CharacteristicId): Result<ByteArray> {
            val deferred = kotlinx.coroutines.CompletableDeferred<ByteArray>()
            return try {
                enqueueOperation(GattOperation.Read(characteristic, deferred))
                Result.success(deferred.await())
            } catch (e: BleOperationCancelled) {
                Result.failure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /**
         * Enqueue a characteristic write and await the result.
         *
         * cancel-safe: see [connect] rationale.
         * Privacy: characteristic UUID logged; payload bytes are NEVER logged.
         */
        override suspend fun write(
            characteristic: CharacteristicId,
            payload: ByteArray,
        ): Result<Unit> {
            val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
            return try {
                enqueueOperation(GattOperation.Write(characteristic, payload, deferred))
                deferred.await()
                Result.success(Unit)
            } catch (e: BleOperationCancelled) {
                Result.failure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /**
         * Cold flow of notification payloads for [characteristic].
         *
         * cancel-safe: backed by a filtered SharedFlow from [GattTransport.notificationFlow];
         * cancellation unsubscribes cleanly.
         * Privacy: payload bytes are NEVER logged.
         */
        override fun notifications(characteristic: CharacteristicId): Flow<ByteArray> =
            gattTransport.notificationFlow(characteristic)

        /**
         * Disconnect the GATT connection and cancel all pending operations. Idempotent.
         *
         * cancel-safe: no suspension after state transition; second call is a no-op.
         */
        override suspend fun disconnect() {
            val current = _connectionState.value
            if (current == BleConnectionState.Disconnected ||
                current == BleConnectionState.Disconnecting
            ) {
                return
            }
            _connectionState.value = BleConnectionState.Disconnecting
            Timber.d("BLE: disconnect requested")

            // Cancel everything already queued so pending callers fail fast with a typed
            // BleOperationCancelled and the queue has room for the Disconnect signal.
            cancelPendingOperations()

            // Signal the actor to perform gattTransport.disconnect() on its own (single)
            // context. Use a suspending send (NOT trySend) so the Disconnect message can
            // never be silently dropped when the queue is full. The idempotency guard above
            // ensures this runs at most once per connected session, and the actor is still
            // alive to receive it (it only stops after processing Disconnect).
            try {
                operationQueue.send(GattOperation.Disconnect)
            } catch (_: ClosedSendChannelException) {
                // Actor already torn down (channel closed by a prior processDisconnect) — nothing to signal.
            }
        }

        // -------------------------------------------------------------------------
        // Internal — actor loop
        // -------------------------------------------------------------------------

        /**
         * Single-actor coroutine that serializes all GATT operations.
         * Only ONE operation is in flight at a time.
         * All [gattTransport] method calls happen exclusively in this coroutine.
         */
        private fun startActor() {
            actorJob =
                clientScope.launch {
                    Timber.d("BLE: actor started")
                    for (operation in operationQueue) {
                        if (!isActive) break
                        processOperation(operation)
                        if (operation is GattOperation.Disconnect) break
                    }
                    Timber.d("BLE: actor stopped")
                }
        }

        /**
         * Dispatch a single [GattOperation] to the GATT transport layer.
         * All calls to [gattTransport] happen in this function — called only from the actor.
         */
        private suspend fun processOperation(operation: GattOperation) {
            when (operation) {
                is GattOperation.Connect -> processConnect(operation)
                is GattOperation.Read -> processRead(operation)
                is GattOperation.Write -> processWrite(operation)
                is GattOperation.SetNotify -> processSetNotify(operation)
                is GattOperation.Disconnect -> processDisconnect()
            }
        }

        private suspend fun processConnect(op: GattOperation.Connect) {
            var attempt = 0
            while (attempt <= BleConstants.RECONNECT_MAX_ATTEMPTS) {
                try {
                    withTimeout(BleConstants.GATT_CONNECT_TIMEOUT_MS) {
                        gattTransport.connect()
                        gattTransport.discoverServices()
                        val negotiatedMtu = gattTransport.requestMtu(BleConstants.PREFERRED_MTU)
                        Timber.d("BLE: connected, negotiated MTU=$negotiatedMtu")
                    }
                    _connectionState.value = BleConnectionState.Connected
                    op.deferred.complete(Unit)
                    return
                } catch (e: CancellationException) {
                    // Caller cancelled or actor is shutting down — propagate, don't retry.
                    op.deferred.completeExceptionally(BleOperationCancelled("Connect cancelled"))
                    throw e
                } catch (e: Exception) {
                    attempt++
                    if (attempt > BleConstants.RECONNECT_MAX_ATTEMPTS) {
                        Timber.e("BLE: connect failed after $attempt attempts")
                        _connectionState.value = BleConnectionState.Failed
                        op.deferred.completeExceptionally(e)
                        cancelPendingOperations()
                        return
                    }
                    val backoffMs =
                        min(
                            BleConstants.RECONNECT_BASE_DELAY_MS * (1L shl (attempt - 1)),
                            MAX_RECONNECT_DELAY_MS,
                        )
                    Timber.d("BLE: connect attempt $attempt failed, retrying in ${backoffMs}ms")
                    delay(backoffMs)
                }
            }
        }

        private suspend fun processRead(op: GattOperation.Read) {
            try {
                val bytes =
                    withTimeout(BleConstants.GATT_OPERATION_TIMEOUT_MS) {
                        gattTransport.readCharacteristic(op.characteristicId)
                    }
                // Privacy: bytes are never logged.
                Timber.d("BLE: read characteristic=${op.characteristicId.value} length=${bytes.size}")
                op.deferred.complete(bytes)
            } catch (e: CancellationException) {
                op.deferred.completeExceptionally(BleOperationCancelled("Read cancelled"))
                throw e
            } catch (e: Exception) {
                Timber.e("BLE: read failed characteristic=${op.characteristicId.value}")
                op.deferred.completeExceptionally(e)
            }
        }

        private suspend fun processWrite(op: GattOperation.Write) {
            try {
                withTimeout(BleConstants.GATT_OPERATION_TIMEOUT_MS) {
                    // Privacy: payload bytes are never logged.
                    gattTransport.writeCharacteristic(op.characteristicId, op.payload)
                }
                Timber.d("BLE: write characteristic=${op.characteristicId.value} length=${op.payload.size}")
                op.deferred.complete(Unit)
            } catch (e: CancellationException) {
                op.deferred.completeExceptionally(BleOperationCancelled("Write cancelled"))
                throw e
            } catch (e: Exception) {
                Timber.e("BLE: write failed characteristic=${op.characteristicId.value}")
                op.deferred.completeExceptionally(e)
            }
        }

        private suspend fun processSetNotify(op: GattOperation.SetNotify) {
            try {
                withTimeout(BleConstants.GATT_OPERATION_TIMEOUT_MS) {
                    gattTransport.setNotification(op.characteristicId, op.enable)
                }
                Timber.d("BLE: setNotify characteristic=${op.characteristicId.value} enable=${op.enable}")
                op.deferred.complete(Unit)
            } catch (e: CancellationException) {
                op.deferred.completeExceptionally(BleOperationCancelled("SetNotify cancelled"))
                throw e
            } catch (e: Exception) {
                Timber.e("BLE: setNotify failed characteristic=${op.characteristicId.value}")
                op.deferred.completeExceptionally(e)
            }
        }

        private suspend fun processDisconnect() {
            // Cancel anything that slipped into the queue behind the Disconnect signal.
            cancelPendingOperations()
            gattTransport.disconnect()
            targetCamera = null
            _connectionState.value = BleConnectionState.Disconnected
            // Close the queue: the actor stops after this op, so any operation enqueued
            // afterwards must fail fast (trySend -> isFailure -> BleOperationCancelled in
            // enqueueOperation) rather than block forever on a stopped actor. A new session
            // requires a fresh client instance.
            operationQueue.close()
            Timber.d("BLE: disconnected")
        }

        /**
         * Cancel all operations currently pending in the queue with [BleOperationCancelled].
         * Called on disconnect or terminal failure.
         */
        private fun cancelPendingOperations() {
            while (true) {
                val pending = operationQueue.tryReceive().getOrNull() ?: break
                when (pending) {
                    is GattOperation.Connect -> {
                        pending.deferred.completeExceptionally(
                            BleOperationCancelled("Disconnected — operation cancelled"),
                        )
                    }

                    is GattOperation.Read -> {
                        pending.deferred.completeExceptionally(
                            BleOperationCancelled("Disconnected — operation cancelled"),
                        )
                    }

                    is GattOperation.Write -> {
                        pending.deferred.completeExceptionally(
                            BleOperationCancelled("Disconnected — operation cancelled"),
                        )
                    }

                    is GattOperation.SetNotify -> {
                        pending.deferred.completeExceptionally(
                            BleOperationCancelled("Disconnected — operation cancelled"),
                        )
                    }

                    is GattOperation.Disconnect -> { /* already processing */ }
                }
            }
        }

        /**
         * Send an operation to the actor queue.
         * Throws if the channel is closed (e.g. client scope was cancelled).
         */
        private fun enqueueOperation(operation: GattOperation) {
            val result = operationQueue.trySend(operation)
            if (result.isFailure) {
                val pending =
                    when (operation) {
                        is GattOperation.Connect -> operation.deferred
                        is GattOperation.Read -> operation.deferred
                        is GattOperation.Write -> operation.deferred
                        is GattOperation.SetNotify -> operation.deferred
                        is GattOperation.Disconnect -> null
                    }
                pending?.completeExceptionally(
                    BleOperationCancelled("Operation queue full or closed"),
                )
            }
        }

        companion object {
            private const val MAX_RECONNECT_DELAY_MS: Long = 30_000L
        }
    }

/**
 * Thrown when a pending GATT operation is cancelled because [AndroidFujiBleClient.disconnect]
 * was called or the client transitioned to [BleConnectionState.Failed].
 *
 * NOT a [CancellationException] — callers should catch this separately from coroutine cancellation
 * and surface it as a typed error.
 */
class BleOperationCancelled(
    message: String,
) : Exception(message)
