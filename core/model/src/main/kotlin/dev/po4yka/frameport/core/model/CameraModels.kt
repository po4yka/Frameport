package dev.po4yka.frameport.core.model

@JvmInline
value class CameraId(val value: String)

@JvmInline
value class CameraModelName(val value: String)

@JvmInline
value class FirmwareVersion(val value: String)

data class CameraSummary(
    val id: CameraId,
    val modelName: CameraModelName,
    val firmwareVersion: FirmwareVersion?,
    val supportedTransports: Set<TransportKind>,
    val connectionStatus: ConnectionStatus,
)

enum class TransportKind {
    WifiPtpIp,
    BluetoothLe,
    UsbPtp,
}

@JvmInline
value class MediaObjectId(val value: String)

enum class MediaFormat {
    Jpeg,
    Raw,
    Heif,
    Movie,
    Unknown,
}

data class MediaObject(
    val id: MediaObjectId,
    val fileName: String?,
    val format: MediaFormat,
    val sizeBytes: Long?,
    val capturedAtEpochMillis: Long?,
)

enum class ImportStatus {
    Queued,
    Importing,
    Imported,
    Failed,
    Cancelled,
}

enum class ConnectionStatus {
    Disconnected,
    Searching,
    Connecting,
    Connected,
    Disconnecting,
    Failed,
}

sealed interface FrameportError {
    val message: String

    data class PermissionDenied(
        val permission: String,
        override val message: String,
    ) : FrameportError

    data class TransportUnavailable(
        val transportKind: TransportKind,
        override val message: String,
    ) : FrameportError

    data class ProtocolUnavailable(
        override val message: String,
    ) : FrameportError

    data class MediaUnavailable(
        val mediaObjectId: MediaObjectId?,
        override val message: String,
    ) : FrameportError

    data class Unknown(
        override val message: String,
    ) : FrameportError
}
