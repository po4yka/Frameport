package dev.po4yka.frameport.nativebridge

import dev.po4yka.frameport.core.model.CameraId

interface NativeFujiSdk {
    val diagnosticState: NativeFujiDiagnosticState

    fun version(): String

    fun initialize(): Result<Unit>

    fun shutdown(): Result<Unit>

    fun openNoopSession(): Result<NativeCameraSession>

    fun closeSession(session: NativeCameraSession): Result<Unit>

    fun openWifiSession(
        commandFd: Int,
        endpointMetadata: String,
    ): Result<NativeCameraSession>

    /**
     * Returns a serialised media-object-list payload. Deserialisation is
     * deferred to a future milestone; callers receive the raw bytes and must
     * not assume any encoding beyond "non-empty means data is present".
     */
    fun listMedia(session: NativeCameraSession): Result<ByteArray>

    /**
     * Returns a serialised thumbnail payload for [objectHandle]. Raw bytes;
     * deserialisation is deferred to a future milestone.
     */
    fun getThumbnail(
        session: NativeCameraSession,
        objectHandle: Long,
    ): Result<ByteArray>

    /**
     * Stream the object identified by [objectHandle] from the camera into the
     * Android-owned [outputFd]. Rust dups the fd and closes its copy when
     * done; the caller must close the original after this call returns.
     */
    fun downloadObjectToFd(
        session: NativeCameraSession,
        objectHandle: Long,
        outputFd: Int,
    ): Result<Unit>

    fun cancelTransfer(transferId: Long): Result<Unit>
}

data class NativeCameraSession(
    val id: Long,
    val cameraId: CameraId?,
    val isNoOp: Boolean,
) {
    companion object {
        val NoOp =
            NativeCameraSession(
                id = -1L,
                cameraId = null,
                isNoOp = true,
            )
    }
}

data class NativeFujiDiagnosticState(
    val isNativeLibraryLoaded: Boolean,
    val message: String,
    val failure: String? = null,
)
