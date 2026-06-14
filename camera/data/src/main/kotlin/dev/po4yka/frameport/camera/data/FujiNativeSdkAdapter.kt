package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.CameraMediaObject
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.EndpointMetadata
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.TransferId
import dev.po4yka.frameport.camera.api.TransferProgress
import dev.po4yka.frameport.core.common.di.IoDispatcher
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.nativebridge.NativeCameraSession
import dev.po4yka.frameport.nativebridge.NativeFujiSdk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
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
 * fd ownership: the caller must have dup'd the fd before passing it in. Rust takes
 * ownership and closes its copy. See docs/rust/fd-ownership.md and ADR-0002.
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

        // ─── Helpers ─────────────────────────────────────────────────────────────

        /**
         * Re-wraps any native failure as a typed, redacted [FrameportException] so no
         * untyped JNI throwable escapes this adapter into the domain layer. On success
         * the result passes through unchanged.
         */
        private fun <T> Result<T>.toTypedFailure(): Result<T> =
            recoverCatching { throwable -> throw FrameportException(throwable.toRedactedFrameportError()) }
    }
