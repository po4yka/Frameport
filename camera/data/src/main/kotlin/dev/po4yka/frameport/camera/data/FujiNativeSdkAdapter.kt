package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.CameraMediaObject
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.EndpointMetadata
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.ShutterAction
import dev.po4yka.frameport.camera.api.TransferId
import dev.po4yka.frameport.camera.api.TransferProgress
import dev.po4yka.frameport.core.common.di.IoDispatcher
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.nativebridge.LiveViewFrameCallback
import dev.po4yka.frameport.nativebridge.NativeCameraSession
import dev.po4yka.frameport.nativebridge.NativeFujiSdk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapts the low-level [NativeFujiSdk] JNI interface (from :native:fuji-rust-android)
 * to the domain-level [FujiNativeSdk] interface (from :camera:api).
 *
 * Type mappings:
 * - [SessionId] <-> [NativeCameraSession.id] (Long)
 * - [CameraObjectHandle] <-> objectHandle (Long)
 * - [TransferId] <-> transferId (Long)
 *
 * Wire format: [NativeFujiSdk.listMedia] currently returns raw bytes with no defined
 * encoding in M06/M08. Empty bytes -> empty list. A real deserializer will be added
 * in M09 once the Rust-side serialization format is stabilized.
 * TODO(M09): Implement ByteArray -> List<CameraMediaObject> deserialization.
 *
 * Transfer progress: [NativeFujiSdk.downloadObjectToFd] is synchronous in M06 and
 * returns Result<Unit>. This adapter emits a single terminal [TransferProgress] stub.
 * TODO(M09): Replace with a real streaming progress callback from Rust JNI.
 *
 * fd ownership for [downloadObjectToFd]: [outputFd] is ANDROID-OWNED and BORROWED by Rust.
 * Rust dups the fd internally and closes only its own dup. Android must NOT call detachFd()
 * and must close the original ParcelFileDescriptor after the transfer terminates.
 * See docs/rust/fd-ownership.md and ADR-0002.
 */
@Singleton
class FujiNativeSdkAdapter
    @Inject
    constructor(
        private val nativeFujiSdk: NativeFujiSdk,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : FujiNativeSdk {
        // cancel-safe: withContext propagates cancellation; blocking JNI call is wrapped.
        override suspend fun openWifiSession(
            socketFd: Int,
            endpointMetadata: EndpointMetadata,
        ): Result<SessionId> =
            withContext(ioDispatcher) {
                val metaJson = """{"host":"${endpointMetadata.host}","port":${endpointMetadata.port}}"""
                nativeFujiSdk
                    .openWifiSession(socketFd, metaJson)
                    .map { session -> SessionId(session.id) }
                    .toTypedFailure()
            }

        // cancel-safe: single withContext; best-effort cleanup; no shared state after cancellation.
        override suspend fun closeSession(sessionId: SessionId) {
            withContext(ioDispatcher) {
                nativeFujiSdk.closeSession(NativeCameraSession(sessionId.value, null, false))
            }
        }

        // cancel-safe: single withContext call; list result is atomic.
        override suspend fun listMedia(sessionId: SessionId): Result<List<CameraMediaObject>> =
            withContext(ioDispatcher) {
                nativeFujiSdk
                    .listMedia(NativeCameraSession(sessionId.value, null, false))
                    .map { bytes ->
                        // TODO(M09): Deserialize bytes into List<CameraMediaObject> using the
                        //   Rust-side wire format once stabilized. For now, empty bytes -> empty list.
                        if (bytes.isEmpty()) emptyList() else emptyList<CameraMediaObject>()
                    }.toTypedFailure()
            }

        // cancel-safe: single withContext call; thumbnail bytes are fetched atomically.
        override suspend fun getThumbnail(
            sessionId: SessionId,
            objectHandle: CameraObjectHandle,
        ): Result<ByteArray> =
            withContext(ioDispatcher) {
                nativeFujiSdk
                    .getThumbnail(
                        NativeCameraSession(sessionId.value, null, false),
                        objectHandle.value,
                    ).toTypedFailure()
            }

        // NOT cancel-safe: writes to outputFd; partial writes are not rolled back on cancellation.
        override fun downloadObjectToFd(
            sessionId: SessionId,
            objectHandle: CameraObjectHandle,
            outputFd: Int,
        ): Flow<TransferProgress> =
            flow {
                // TODO(M09): Replace with real streaming progress callbacks from Rust JNI.
                //   The Rust side currently returns Result<Unit> synchronously.
                val result =
                    nativeFujiSdk.downloadObjectToFd(
                        NativeCameraSession(sessionId.value, null, false),
                        objectHandle.value,
                        outputFd,
                    )
                result.fold(
                    onSuccess = {
                        // Emit a stub terminal progress event; real size unknown until M09.
                        emit(TransferProgress(TransferId(objectHandle.value), 0L, 0L))
                    },
                    onFailure = { throwable ->
                        // Map raw JNI throwables to a typed, redacted error before they
                        // cross into the domain layer (typed-error boundary).
                        throw FrameportException(throwable.toRedactedFrameportError())
                    },
                )
            }.flowOn(ioDispatcher)

        // cancel-safe: single delegated call; idempotent.
        override suspend fun cancelTransfer(transferId: TransferId) {
            withContext(ioDispatcher) {
                nativeFujiSdk.cancelTransfer(transferId.value)
            }
        }

        // cancel-safe: callbackFlow with awaitClose; cancellation triggers awaitClose block
        // which calls nativeLiveViewStop. The Rust stop flag is set atomically; the worker
        // thread drains and exits. No shared mutable state is mutated after cancellation.
        //
        // Latest-frame-wins contract: BufferOverflow.DROP_OLDEST ensures the Rust read loop
        // is never blocked on a slow Compose consumer. Callers must not assume every frame
        // is delivered — only the most recent frame is guaranteed at any point.
        //
        // fd ownership: liveViewFd is Android-owned and already dup'd by the caller (via
        // CameraWifiConnector.openLiveViewSocket). Rust dups it again inside nativeLiveViewStart
        // and takes exclusive ownership of the dup. Android MUST NOT close liveViewFd after
        // this flow is collected. See docs/rust/fd-ownership.md and ADR-0002.
        override fun liveViewFrames(
            sessionId: SessionId,
            liveViewFd: Int,
        ): Flow<ByteArray> =
            // capacity = 2: one frame being decoded by the consumer, one buffered.
            // onBufferOverflow = DROP_OLDEST: latest-frame-wins; the Rust read loop
            // is never blocked waiting for a slow Compose consumer.
            callbackFlow<ByteArray> {
                // Register a callback that forwards each JPEG frame into the channel.
                // trySend never blocks (channel uses DROP_OLDEST overflow policy).
                // jpeg.copyOf(): the Rust ring-buffer slice is valid only for the
                // duration of the callback; we must copy before returning.
                val callback =
                    LiveViewFrameCallback { jpeg ->
                        channel.trySend(jpeg.copyOf())
                        // DROP_OLDEST policy: ChannelResult.isFailure means a frame was
                        // dropped — expected at high frame rates with a slow consumer.
                        // latest-frame-wins, so we intentionally ignore the result.
                        Unit
                    }

                // Start the Rust read loop. On success, the worker thread is running.
                val startResult = nativeFujiSdk.nativeLiveViewStart(sessionId.value, liveViewFd, callback)
                if (startResult.isFailure) {
                    close(startResult.exceptionOrNull() ?: IllegalStateException("nativeLiveViewStart failed"))
                    return@callbackFlow
                }

                // awaitClose is called when the collector cancels or the flow is closed.
                // It stops the Rust read loop cleanly (sets the stop flag + joins the worker).
                awaitClose {
                    nativeFujiSdk.nativeLiveViewStop(sessionId.value)
                }
            }
                // Latest-frame-wins: a plain callbackFlow buffers with SUSPEND and trySend
                // would DROP the NEW frame when full (keeping stale frames). buffer(1,
                // DROP_OLDEST) fuses into the callbackFlow channel so trySend always succeeds
                // by evicting the OLDEST queued frame — the camera read loop never blocks and
                // the consumer always sees the most recent frame.
                .buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                .flowOn(ioDispatcher)

        // cancel-safe: single withContext wrapping the JNI bridge; no shared mutable state
        // mutated after cancellation. The dup'd fd passed as usbFd is owned by Rust from this
        // point; Android must not close it. See docs/rust/fd-ownership.md and ADR-0002.
        override suspend fun openUsbSession(
            usbFd: Int,
            descriptors: ByteArray,
        ): Result<SessionId> =
            withContext(ioDispatcher) {
                nativeFujiSdk
                    .openUsbSession(usbFd, descriptors)
                    .map { rawId -> SessionId(rawId) }
                    .toTypedFailure()
            }

        // cancel-safe: single withContext call; no shared mutable state mutated after cancellation.
        override suspend fun closeUsbSession(sessionId: SessionId) {
            withContext(ioDispatcher) {
                nativeFujiSdk.closeUsbSession(sessionId.value)
            }
        }

        // cancel-safe: single withContext; stub returns success; no shared state mutated after cancellation.
        // TODO(M16): wire to fuji-ffi JNI entry point for RemoteSession shutter command.
        @Suppress("UNUSED_PARAMETER")
        override suspend fun remoteShutter(
            sessionId: SessionId,
            action: ShutterAction,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                // Deferred JNI: the Rust RemoteSession is tested via fuji-sim.
                // Return stub success so the Hilt graph compiles and callers can exercise the path.
                Result.success(Unit)
            }

        // ─── Helpers ─────────────────────────────────────────────────────────────

        /**
         * Re-wraps any native failure as a typed, redacted [FrameportException] so no
         * untyped JNI throwable escapes this adapter into the domain layer. On success
         * the result passes through unchanged.
         */
        private fun <T> Result<T>.toTypedFailure(): Result<T> =
            recoverCatching { throwable -> throw FrameportException(throwable.toRedactedFrameportError()) }
    }
