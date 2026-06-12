package dev.po4yka.frameport.camera.wifi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface CameraWifiConnector {
    val state: StateFlow<CameraWifiState>

    suspend fun requestCameraNetwork(credentials: CameraWifiCredentials): Result<CameraNetworkHandle>

    suspend fun openBoundSocket(handle: CameraNetworkHandle, endpoint: CameraEndpoint): Result<OwnedSocketHandle>

    suspend fun release(handle: CameraNetworkHandle)
}

data class CameraWifiCredentials(
    val ssid: String,
    val passphrase: String?,
)

data class CameraEndpoint(
    val host: String,
    val port: Int,
)

@JvmInline
value class CameraNetworkHandle(val value: String)

@JvmInline
value class OwnedSocketHandle(val fd: Int)

enum class CameraWifiState {
    Idle,
    RequestingNetwork,
    NetworkAvailable,
    OpeningSocket,
    Ready,
    Releasing,
    Failed,
}

class NoOpCameraWifiConnector : CameraWifiConnector {
    private val wifiState = MutableStateFlow(CameraWifiState.Idle)

    override val state: StateFlow<CameraWifiState> = wifiState

    override suspend fun requestCameraNetwork(credentials: CameraWifiCredentials): Result<CameraNetworkHandle> =
        Result.failure(IllegalStateException("Wi-Fi camera routing is not implemented."))

    override suspend fun openBoundSocket(
        handle: CameraNetworkHandle,
        endpoint: CameraEndpoint,
    ): Result<OwnedSocketHandle> = Result.failure(IllegalStateException("Wi-Fi socket binding is not implemented."))

    override suspend fun release(handle: CameraNetworkHandle) {
        wifiState.value = CameraWifiState.Idle
    }
}
