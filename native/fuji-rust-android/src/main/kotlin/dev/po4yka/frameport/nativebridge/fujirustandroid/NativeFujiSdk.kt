package dev.po4yka.frameport.nativebridge.fujirustandroid

import dev.po4yka.frameport.core.model.CameraId
import dev.po4yka.frameport.core.model.MediaObject

interface NativeFujiSdk {
    suspend fun openWifiSession(socketFd: Int): Result<NativeCameraSession>

    suspend fun listMedia(session: NativeCameraSession): Result<List<MediaObject>>

    suspend fun closeSession(session: NativeCameraSession)
}

data class NativeCameraSession(
    val id: String,
    val cameraId: CameraId?,
)

class NoOpNativeFujiSdk : NativeFujiSdk {
    override suspend fun openWifiSession(socketFd: Int): Result<NativeCameraSession> =
        Result.failure(IllegalStateException("Native Fuji SDK JNI bridge is not implemented."))

    override suspend fun listMedia(session: NativeCameraSession): Result<List<MediaObject>> =
        Result.failure(IllegalStateException("Native Fuji SDK JNI bridge is not implemented."))

    override suspend fun closeSession(session: NativeCameraSession) = Unit
}
