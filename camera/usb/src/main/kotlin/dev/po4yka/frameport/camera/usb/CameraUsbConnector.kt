package dev.po4yka.frameport.camera.usb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface CameraUsbConnector {
    val state: StateFlow<CameraUsbState>

    suspend fun requestPermission(device: CameraUsbDeviceRef): Result<Unit>

    suspend fun open(device: CameraUsbDeviceRef): Result<CameraUsbConnection>

    suspend fun close(connection: CameraUsbConnection)
}

data class CameraUsbDeviceRef(
    val id: String,
    val displayName: String?,
)

@JvmInline
value class CameraUsbConnection(val fd: Int)

enum class CameraUsbState {
    Idle,
    RequestingPermission,
    Opening,
    Ready,
    Closing,
    Failed,
}

class NoOpCameraUsbConnector : CameraUsbConnector {
    private val usbState = MutableStateFlow(CameraUsbState.Idle)

    override val state: StateFlow<CameraUsbState> = usbState

    override suspend fun requestPermission(device: CameraUsbDeviceRef): Result<Unit> =
        Result.failure(IllegalStateException("USB support is not implemented."))

    override suspend fun open(device: CameraUsbDeviceRef): Result<CameraUsbConnection> =
        Result.failure(IllegalStateException("USB support is not implemented."))

    override suspend fun close(connection: CameraUsbConnection) {
        usbState.value = CameraUsbState.Idle
    }
}
