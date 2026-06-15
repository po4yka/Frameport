package dev.po4yka.frameport.camera.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.CharacteristicId
import dev.po4yka.frameport.core.common.di.IoDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Android implementation of [GattTransport].
 *
 * Wraps [BluetoothGatt] and its callback, exposing suspend functions that the
 * [AndroidFujiBleClient] actor calls to perform GATT operations.
 *
 * Threading contract:
 * - All BluetoothGatt method calls are invoked from the IoDispatcher (via [withContext]).
 * - The GattCallback fires on the Android BLE thread. Results are forwarded to
 *   [CompletableDeferred] instances that the actor's suspending callers await.
 * - Only one [CompletableDeferred] is "active" at a time (the one set before each GATT call).
 *   This is enforced by the single-actor queue in [AndroidFujiBleClient].
 *
 * Privacy: No BLE MAC address, raw payload bytes, or passphrase data is logged.
 *
 * NOTE: This class requires a [BluetoothDevice] to be set before [connect] is called.
 * In production, [AndroidBleScanner] calls [setTargetDevice] after discovering the camera.
 * In tests, [FakeGattTransport] is used instead.
 */
@Singleton
internal class AndroidGattTransport
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : GattTransport {
        private val _connectionState = MutableStateFlow(BleConnectionState.Disconnected)

        // cancel-safe: StateFlow; read-only projection.
        override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

        /** Shared flow of (characteristicUuid, payload) pairs from the GATT callback thread. */
        private val notificationSharedFlow =
            MutableSharedFlow<Pair<String, ByteArray>>(
                extraBufferCapacity = 64,
            )

        /** The BLE device to connect to. Set by [setTargetDevice] before [connect]. */
        @Volatile
        private var targetDevice: BluetoothDevice? = null

        /** Active GATT connection, null when disconnected. */
        @Volatile
        private var gatt: BluetoothGatt? = null

        /**
         * Monotonically increasing generation counter, incremented at the start of each [connect]
         * call before [pendingConnectSlot] is installed. The GATT callback reads this value and
         * compares it against [ConnectSlot.generation] to decide whether the callback belongs to
         * the current connect attempt or a stale one from a previous call.
         */
        private val connectGeneration = AtomicInteger(0)

        /**
         * Pairs an expected generation with the deferred that [connect] is awaiting.
         * Written by [connect] (actor thread) and read by [onConnectionStateChange] (BLE thread).
         * [Volatile] ensures the BLE thread always sees the latest reference.
         *
         * A late [onConnectionStateChange] callback from a previous connect attempt carries a
         * [ConnectSlot.generation] that is less than the current [connectGeneration] — it is
         * silently dropped rather than completing the NEW deferred prematurely.
         */
        private data class ConnectSlot(
            val generation: Int,
            val deferred: CompletableDeferred<Unit>,
        )

        @Volatile private var pendingConnectSlot: ConnectSlot? = null

        /**
         * Precomputed characteristic-UUID to service-UUID lookup map, built once in
         * [discoverServices] after [onServicesDiscovered] fires. Replaces the O(n*m) linear
         * scan in [findServiceForCharacteristic] with an O(1) map lookup on every GATT operation.
         * Cleared in [disconnect] so a reconnect always rebuilds from fresh service data.
         * Written only from the actor coroutine; [Volatile] makes the write visible to any reader.
         */
        @Volatile private var characteristicServiceMap: Map<UUID, UUID> = emptyMap()

        @Volatile private var pendingServices: CompletableDeferred<Unit>? = null

        @Volatile private var pendingMtu: CompletableDeferred<Int>? = null

        @Volatile private var pendingRead: CompletableDeferred<ByteArray>? = null

        @Volatile private var pendingWrite: CompletableDeferred<Unit>? = null

        @Volatile private var pendingDescriptorWrite: CompletableDeferred<Unit>? = null

        // -------------------------------------------------------------------------
        // GattTransport API
        // -------------------------------------------------------------------------

        /**
         * Set the BLE device that will be connected on the next [connect] call.
         * Called by [AndroidBleScanner] after a camera is selected from scan results.
         * Privacy: device address is NOT logged.
         */
        fun setTargetDevice(device: BluetoothDevice) {
            targetDevice = device
        }

        // cancel-safe: suspends on a CompletableDeferred; cancellation clears pendingConnectSlot.
        @SuppressLint("MissingPermission")
        override suspend fun connect() {
            val device =
                requireNotNull(targetDevice) {
                    "setTargetDevice must be called before connect()"
                }
            val deferred = CompletableDeferred<Unit>()
            // Increment the generation BEFORE installing the slot. Any onConnectionStateChange
            // callback that fires with a stale (lower) generation value will see a mismatch
            // when it reads pendingConnectSlot.generation and will be silently dropped.
            val generation = connectGeneration.incrementAndGet()
            pendingConnectSlot = ConnectSlot(generation, deferred)
            withContext(ioDispatcher) {
                // connectGatt with autoConnect=false for manual reconnect control.
                // Privacy: device address not logged.
                Timber.d("BLE: initiating GATT connect generation=$generation")
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    ?: throw IllegalStateException("connectGatt returned null")
            }
            try {
                deferred.await()
            } finally {
                pendingConnectSlot = null
            }
        }

        // cancel-safe: suspends on a CompletableDeferred; cancellation is safe.
        @SuppressLint("MissingPermission")
        override suspend fun discoverServices() {
            val activeGatt = requireNotNull(gatt) { "discoverServices called without active GATT" }
            val deferred = CompletableDeferred<Unit>()
            pendingServices = deferred
            withContext(ioDispatcher) {
                val started = activeGatt.discoverServices()
                if (!started) {
                    pendingServices = null
                    deferred.completeExceptionally(
                        IllegalStateException("discoverServices() returned false"),
                    )
                }
            }
            try {
                deferred.await()
                // Build the characteristic -> service lookup map once, immediately after service
                // discovery completes. All subsequent GATT operations use O(1) map lookup instead
                // of the O(n*m) linear scan over activeGatt.services on every call.
                val map = mutableMapOf<UUID, UUID>()
                for (service in activeGatt.services) {
                    for (chr in service.characteristics) {
                        map[chr.uuid] = service.uuid
                    }
                }
                characteristicServiceMap = map
                Timber.d("BLE: characteristic-service map built entries=${map.size}")
            } finally {
                pendingServices = null
            }
        }

        // cancel-safe: MTU negotiation is advisory; cancellation leaves the connection intact.
        @SuppressLint("MissingPermission")
        override suspend fun requestMtu(mtu: Int): Int {
            val activeGatt = requireNotNull(gatt) { "requestMtu called without active GATT" }
            val deferred = CompletableDeferred<Int>()
            pendingMtu = deferred
            withContext(ioDispatcher) {
                val requested = activeGatt.requestMtu(mtu)
                if (!requested) {
                    pendingMtu = null
                    deferred.complete(BleConstants.PREFERRED_MTU) // best-effort; return default
                }
            }
            return try {
                deferred.await()
            } finally {
                pendingMtu = null
            }
        }

        // cancel-safe: read is atomic at GATT level; cancellation discards the result safely.
        @SuppressLint("MissingPermission")
        override suspend fun readCharacteristic(characteristicId: CharacteristicId): ByteArray {
            val activeGatt = requireNotNull(gatt) { "readCharacteristic called without active GATT" }
            val characteristic =
                activeGatt
                    .getService(
                        findServiceForCharacteristic(characteristicId.value),
                    )?.getCharacteristic(UUID.fromString(characteristicId.value))
                    ?: throw IllegalStateException(
                        "Characteristic not found: ${characteristicId.value}",
                    )
            val deferred = CompletableDeferred<ByteArray>()
            pendingRead = deferred
            withContext(ioDispatcher) {
                @Suppress("DEPRECATION")
                val initiated = activeGatt.readCharacteristic(characteristic)
                if (!initiated) {
                    pendingRead = null
                    deferred.completeExceptionally(
                        IllegalStateException("readCharacteristic() returned false"),
                    )
                }
            }
            return try {
                deferred.await()
            } finally {
                pendingRead = null
            }
        }

        // cancel-safe: write is dispatched atomically; cancellation is safe.
        @SuppressLint("MissingPermission")
        override suspend fun writeCharacteristic(
            characteristicId: CharacteristicId,
            payload: ByteArray,
        ) {
            val activeGatt = requireNotNull(gatt) { "writeCharacteristic called without active GATT" }
            val characteristic =
                activeGatt
                    .getService(
                        findServiceForCharacteristic(characteristicId.value),
                    )?.getCharacteristic(UUID.fromString(characteristicId.value))
                    ?: throw IllegalStateException(
                        "Characteristic not found: ${characteristicId.value}",
                    )
            val deferred = CompletableDeferred<Unit>()
            pendingWrite = deferred
            withContext(ioDispatcher) {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                val initiated = activeGatt.writeCharacteristic(characteristic)
                if (!initiated) {
                    pendingWrite = null
                    deferred.completeExceptionally(
                        IllegalStateException("writeCharacteristic() returned false"),
                    )
                }
            }
            try {
                deferred.await()
            } finally {
                pendingWrite = null
            }
        }

        // cancel-safe: descriptor write is atomic; cancellation is safe.
        @SuppressLint("MissingPermission")
        override suspend fun setNotification(
            characteristicId: CharacteristicId,
            enable: Boolean,
        ) {
            val activeGatt = requireNotNull(gatt) { "setNotification called without active GATT" }
            val characteristic =
                activeGatt
                    .getService(
                        findServiceForCharacteristic(characteristicId.value),
                    )?.getCharacteristic(UUID.fromString(characteristicId.value))
                    ?: throw IllegalStateException(
                        "Characteristic not found for notification: ${characteristicId.value}",
                    )
            val cccdValue = if (enable) BleConstants.CCCD_ENABLE_NOTIFICATION else BleConstants.CCCD_DISABLE_NOTIFICATION
            val deferred = CompletableDeferred<Unit>()
            pendingDescriptorWrite = deferred
            withContext(ioDispatcher) {
                activeGatt.setCharacteristicNotification(characteristic, enable)
                val descriptor =
                    characteristic.getDescriptor(
                        UUID.fromString(BleConstants.CCCD_DESCRIPTOR_UUID),
                    ) ?: run {
                        pendingDescriptorWrite = null
                        deferred.completeExceptionally(
                            IllegalStateException("CCCD descriptor not found for ${characteristicId.value}"),
                        )
                        return@withContext
                    }
                @Suppress("DEPRECATION")
                descriptor.value = cccdValue
                @Suppress("DEPRECATION")
                val initiated = activeGatt.writeDescriptor(descriptor)
                if (!initiated) {
                    pendingDescriptorWrite = null
                    deferred.completeExceptionally(
                        IllegalStateException("writeDescriptor() returned false for CCCD"),
                    )
                }
            }
            try {
                deferred.await()
            } finally {
                pendingDescriptorWrite = null
            }
        }

        // cancel-safe: idempotent; second call is a no-op.
        @SuppressLint("MissingPermission")
        override suspend fun disconnect() {
            withContext(ioDispatcher) {
                gatt?.disconnect()
                gatt?.close()
                gatt = null
            }
            // Clear the lookup map so a subsequent reconnect always rebuilds from fresh data.
            characteristicServiceMap = emptyMap()
            _connectionState.value = BleConnectionState.Disconnected
            Timber.d("BLE: GattTransport disconnected and closed")
        }

        // cancel-safe: cold flow; collection cancellation unsubscribes from SharedFlow cleanly.
        override fun notificationFlow(characteristicId: CharacteristicId): Flow<ByteArray> =
            notificationSharedFlow
                .filter { (uuid, _) -> uuid == characteristicId.value }
                .map { (_, payload) -> payload }

        // -------------------------------------------------------------------------
        // GATT Callback (called on Android BLE thread)
        // -------------------------------------------------------------------------

        private val gattCallback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    gatt: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    // Privacy: gatt.device.address is NOT logged.
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            // Read the current slot atomically. The slot carries both the expected
                            // generation and the deferred, so we can check them together.
                            val slot = pendingConnectSlot
                            val currentGen = connectGeneration.get()
                            Timber.d("BLE: GATT state=CONNECTED status=$status generation=$currentGen")
                            // STATE_CONNECTED means the link-layer connection is up; service
                            // discovery and MTU negotiation still need to run before the session
                            // is fully ready. Emit Connected at the transport layer so that the
                            // upper client (AndroidFujiBleClient) can proceed with discoverServices.
                            // The client only advances its own observable state to Connected after
                            // the full handshake (connect + discoverServices + requestMtu).
                            _connectionState.value = BleConnectionState.Connected
                            // Only resolve the deferred when the slot's generation matches the
                            // current counter. A stale callback (slot.generation < currentGen)
                            // from a previous connect attempt is silently dropped — it must not
                            // complete the deferred that belongs to the newest attempt.
                            if (slot != null && slot.generation == currentGen) {
                                slot.deferred.complete(Unit)
                            } else {
                                Timber.d(
                                    "BLE: stale CONNECTED callback dropped generation=${slot?.generation} current=$currentGen",
                                )
                            }
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Timber.d("BLE: GATT state=DISCONNECTED status=$status")
                            _connectionState.value = BleConnectionState.Disconnected
                            val err = IllegalStateException("GATT disconnected status=$status")
                            pendingConnectSlot?.deferred?.completeExceptionally(err)
                            pendingServices?.completeExceptionally(err)
                            pendingMtu?.completeExceptionally(err)
                            pendingRead?.completeExceptionally(err)
                            pendingWrite?.completeExceptionally(err)
                            pendingDescriptorWrite?.completeExceptionally(err)
                        }
                    }
                }

                override fun onServicesDiscovered(
                    gatt: BluetoothGatt,
                    status: Int,
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Timber.d("BLE: services discovered count=${gatt.services.size}")
                        pendingServices?.complete(Unit)
                    } else {
                        val err = IllegalStateException("onServicesDiscovered status=$status")
                        Timber.e("BLE: service discovery failed status=$status")
                        pendingServices?.completeExceptionally(err)
                    }
                }

                override fun onMtuChanged(
                    gatt: BluetoothGatt,
                    mtu: Int,
                    status: Int,
                ) {
                    Timber.d("BLE: MTU changed mtu=$mtu status=$status")
                    pendingMtu?.complete(mtu)
                }

                // API 33+ value-param overload. The framework calls this (not the deprecated
                // 3-arg) on API 33+, delivering the value directly. Privacy: value bytes NOT logged.
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        pendingRead?.complete(value)
                    } else {
                        pendingRead?.completeExceptionally(
                            IllegalStateException("onCharacteristicRead status=$status"),
                        )
                    }
                }

                // Pre-API-33 fallback: the framework populates characteristic.value before calling
                // this deprecated overload. CompletableDeferred.complete is idempotent, so there is
                // no double-completion hazard if both paths ever fire.
                @Deprecated("Used on API < 33; API 33+ uses the value-param overload above")
                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // Privacy: value bytes are NOT logged.
                        pendingRead?.complete(characteristic.value ?: byteArrayOf())
                    } else {
                        pendingRead?.completeExceptionally(
                            IllegalStateException("onCharacteristicRead status=$status"),
                        )
                    }
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        pendingWrite?.complete(Unit)
                    } else {
                        pendingWrite?.completeExceptionally(
                            IllegalStateException("onCharacteristicWrite status=$status"),
                        )
                    }
                }

                // API 33+ value-param overload — used by the framework on API 33+.
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    // Privacy: payload bytes are NOT logged.
                    notificationSharedFlow.tryEmit(characteristic.uuid.toString() to value)
                }

                // Pre-API-33 fallback (framework populates characteristic.value).
                @Deprecated("Used on API < 33; API 33+ uses the value-param overload above")
                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    // Privacy: payload bytes are NOT logged.
                    val payload = characteristic.value ?: byteArrayOf()
                    val uuid = characteristic.uuid.toString()
                    notificationSharedFlow.tryEmit(uuid to payload)
                }

                @Suppress("DEPRECATION")
                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        pendingDescriptorWrite?.complete(Unit)
                    } else {
                        pendingDescriptorWrite?.completeExceptionally(
                            IllegalStateException("onDescriptorWrite status=$status"),
                        )
                    }
                }
            }

        // -------------------------------------------------------------------------
        // Internal helpers
        // -------------------------------------------------------------------------

        /**
         * Return the service UUID for a given characteristic UUID using the precomputed lookup
         * map built in [discoverServices]. O(1) per call — replaces the former O(n*m) linear
         * scan over [BluetoothGatt.services] that ran on every GATT read/write/notify operation.
         *
         * Throws [IllegalStateException] if the map is empty (discoverServices not yet called) or
         * if the characteristic UUID is absent (not advertised by the connected camera).
         */
        private fun findServiceForCharacteristic(characteristicUuid: String): UUID {
            val chrUuid = UUID.fromString(characteristicUuid)
            return characteristicServiceMap[chrUuid]
                ?: throw IllegalStateException(
                    "No service found for characteristic $characteristicUuid — " +
                        "discoverServices() may not have completed successfully",
                )
        }
    }
