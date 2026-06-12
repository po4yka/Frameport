# Error Model

## Purpose

Frameport needs a typed, cross-layer error model because camera workflows cross several failure-prone boundaries: Android runtime permissions, Bluetooth LE scan/GATT lifecycle, Wi-Fi network request and routing, socket file descriptor handoff, Rust native protocol layer, PTP/PTP-IP session state, camera state, media transfer, Android MediaStore, JNI/native boundaries, and GPS-to-camera EXIF geotagging.

Errors must be useful for user-facing troubleshooting, diagnostics, support bundles, tests, retry decisions, compatibility matrix updates, and Rust/Kotlin boundary safety.

This document is an engineering contract, not a final UI copy spec.

## Core Principles

```text
1. Errors must be typed, not raw strings.
2. Errors must preserve layer information.
3. User cancellation is not a failure.
4. Permission denial is not a protocol error.
5. Android network failure is not a camera protocol error.
6. Camera busy is not an unknown error.
7. Storage failure is not a transfer protocol failure.
8. Rust panics must never cross JNI.
9. Raw native/vendor integer codes must be mapped before reaching UI.
10. Diagnostics must be precise but redacted.
11. Retry policy must be explicit per error category.
12. User-facing messages must be actionable and non-misleading.
```

Frameport's product advantage is not hiding failures behind "connection failed", but showing the smallest safe actionable reason.

## Error Layers

Conceptual layers:

```text
User action layer:
  user cancellation
  user denial
  user rejected network request

Permission layer:
  Android runtime permissions
  Bluetooth permissions
  Wi-Fi permissions
  notification/foreground-service permissions
  location permissions for explicit GPS-to-camera EXIF geotagging

Platform transport layer:
  Bluetooth adapter
  Bluetooth scan
  GATT connection
  Wi-Fi Network
  socket bind/connect
  USB later
  MediaStore

Native boundary layer:
  JNI call
  fd ownership
  callback
  panic boundary
  native session handle

Rust transport layer:
  fd-backed stream
  TCP read/write
  timeout
  connection reset

Protocol layer:
  PTP/PTP-IP framing
  transaction sequencing
  response parsing
  session state
  operation support

Camera state layer:
  camera busy
  unsupported mode
  disconnected
  sleeping/off
  incompatible firmware

Media layer:
  object enumeration
  metadata
  thumbnail
  object transfer
  media format

Storage layer:
  output fd
  MediaStore pending item
  finalization
  cleanup

Diagnostics layer:
  redaction
  bundle creation
  export
```

Every error should identify its source layer.

## Error Taxonomy

Conceptual Kotlin-style shape:

```kotlin
sealed interface FrameportError {
    val code: ErrorCode
    val layer: ErrorLayer
    val recoverability: Recoverability
    val safeMessageKey: String
}
```

```kotlin
enum class ErrorLayer {
    UserAction,
    Permission,
    Bluetooth,
    Wifi,
    Socket,
    Usb,
    NativeBoundary,
    Transport,
    Protocol,
    CameraState,
    MediaTransfer,
    Storage,
    Location,
    Diagnostics,
}
```

```kotlin
enum class Recoverability {
    UserActionRequired,
    Retryable,
    RetryableAfterReconnect,
    NotRetryable,
    Cancelled,
    Unsupported,
    Unknown,
}
```

Top-level families:

```text
Permission.*
Bluetooth.*
Wifi.*
Socket.*
Usb.*
Native.*
Transport.*
Protocol.*
Camera.*
Media.*
Transfer.*
Storage.*
Location.*
Diagnostics.*
User.*
Compatibility.*
```

Exact Kotlin/Rust names may evolve, but the taxonomy must remain typed and layer-aware.

## Permission Errors

```text
Permission.BluetoothScanDenied
Permission.BluetoothConnectDenied
Permission.NearbyWifiDevicesDenied
Permission.NotificationsDenied
Permission.ForegroundServiceDenied
Permission.LocationDenied
Permission.BackgroundLocationDenied
Permission.StorageAccessDenied
Permission.UsbPermissionDenied
```

Guidance:

* Permission errors require user action.
* Do not retry silently.
* Show exact missing permission and feature impact.
* Do not claim camera failure when permission is denied.
* Location permissions are only relevant when the user explicitly enables GPS-to-camera EXIF geotagging.

Example user-facing behavior:

```text
Bluetooth scan permission denied:
  Explain that Frameport needs Bluetooth scanning to discover nearby cameras.

Nearby Wi-Fi devices permission denied:
  Explain that Frameport needs permission to connect to the camera Wi-Fi network.

Location permission denied:
  Explain only if user enabled GPS-to-camera EXIF geotagging.
```

## Bluetooth Errors

```text
Bluetooth.AdapterUnavailable
Bluetooth.AdapterDisabled
Bluetooth.ScanFailed
Bluetooth.ScanTimeout
Bluetooth.NoCameraFound
Bluetooth.UnsupportedDevice
Bluetooth.GattConnectionFailed
Bluetooth.GattDisconnected
Bluetooth.GattTimeout
Bluetooth.MtuRequestFailed
Bluetooth.ServiceDiscoveryFailed
Bluetooth.ServiceNotFound
Bluetooth.CharacteristicNotFound
Bluetooth.ReadTimeout
Bluetooth.WriteTimeout
Bluetooth.NotificationSubscribeFailed
Bluetooth.NotificationStreamClosed
Bluetooth.OperationCancelled
Bluetooth.OperationQueueClosed
Bluetooth.UnexpectedCallback
```

BLE protocol-specific errors:

```text
BleProtocol.MalformedPayload
BleProtocol.InvalidPayloadLength
BleProtocol.UnsupportedCameraState
BleProtocol.PairingRequired
BleProtocol.PairingRejected
BleProtocol.PairingStale
BleProtocol.MissingWifiCredentials
BleProtocol.UnsupportedCharacteristicPayload
BleProtocol.LocationPayloadUnsupported
```

Guidance:

* Distinguish Android BLE transport errors from Fujifilm BLE payload/protocol errors.
* Do not expose raw BLE payloads in user messages.
* Raw BLE data must be redacted in diagnostics by default.
* BLE connection success does not imply Wi-Fi handoff support.

## Wi-Fi and Network Errors

```text
Wifi.UserRejectedNetworkRequest
Wifi.NetworkRequestTimeout
Wifi.NetworkUnavailable
Wifi.NetworkLost
Wifi.NoRouteToCamera
Wifi.SocketBindFailed
Wifi.SocketConnectTimeout
Wifi.InvalidCameraEndpoint
Wifi.CameraNetworkHasNoInternet
Wifi.CallbackTimeout
Wifi.ReleaseFailed
```

Guidance:

* Camera Wi-Fi may be local-only and not provide internet.
* Lack of internet is not automatically an error for camera workflows.
* User rejection should be represented separately from timeout.
* Network lost should be recoverable through reconnect.
* Do not log camera Wi-Fi passphrases.
* SSID may be redacted if personal.

## Socket and File Descriptor Errors

```text
Socket.InvalidFd
Socket.FdDupFailed
Socket.FdOwnershipViolation
Socket.DoubleClosePrevented
Socket.BindToNetworkFailed
Socket.ConnectFailed
Socket.ConnectTimeout
Socket.ClosedDuringOperation
Socket.LeakedFdDetected
Socket.UnsupportedFdState
```

Guidance:

* Fd ownership must be explicit.
* Android must duplicate fds before transferring ownership to Rust if Android keeps its own object.
* Rust must close only Rust-owned fds.
* Fd failures are native/platform boundary errors, not camera protocol errors.
* Diagnostics should include fd state category, not raw private data.

## Native / JNI Errors

```text
Native.LibraryLoadFailed
Native.SymbolMissing
Native.SessionHandleInvalid
Native.PanicPrevented
Native.CallbackFailed
Native.CallbackDropped
Native.InvalidArgument
Native.ResultDecodeFailed
Native.ThreadAttachFailed
Native.UnexpectedNull
Native.UnsupportedAbi
```

Guidance:

* Rust panics must be caught and mapped.
* Panics must not cross JNI.
* JNI calls should return typed results.
* Native errors must not expose raw buffers or secrets.
* User-facing messages should avoid technical panic details unless in debug mode.

## Transport Errors

```text
Transport.ReadFailed
Transport.WriteFailed
Transport.Timeout
Transport.ConnectionReset
Transport.Eof
Transport.SessionClosed
Transport.BackpressureLimitReached
Transport.UnexpectedChannelClose
Transport.ChannelNotOpen
```

Guidance:

* Transport errors are below protocol errors.
* Timeout may be retryable depending on operation.
* Connection reset usually requires session reconnect.
* Channel closure during transfer must produce clear transfer failure.
* Raw packet data must not be logged by default.

## Protocol Errors

```text
Protocol.HandshakeRejected
Protocol.OpenSessionFailed
Protocol.CloseSessionFailed
Protocol.UnexpectedPacket
Protocol.InvalidPacketLength
Protocol.InvalidTransactionId
Protocol.ResponseMismatch
Protocol.UnsupportedOperation
Protocol.UnsupportedFunctionMode
Protocol.OperationTimeout
Protocol.OperationRejected
Protocol.SessionNotOpen
Protocol.SessionAlreadyOpen
Protocol.EventChannelUnavailable
Protocol.LiveViewChannelUnavailable
```

Guidance:

* Protocol errors come from packet/session semantics.
* Unsupported operation should be mapped to compatibility/feature state.
* Invalid packet length must be handled safely.
* Unexpected packets should be diagnosable but redacted.
* Protocol errors may indicate camera firmware/model differences.

## Camera State Errors

```text
Camera.Busy
Camera.Disconnected
Camera.Sleeping
Camera.PoweredOff
Camera.ModeUnsupported
Camera.CardMissing
Camera.CardBusy
Camera.CardFull
Camera.StorageUnavailable
Camera.BatteryLow
Camera.FirmwareUnsupported
Camera.FirmwareUntested
Camera.UserActionRequiredOnCamera
Camera.OperationRejected
Camera.RemoteModeUnavailable
Camera.LocationSyncUnsupported
```

Guidance:

* Camera busy should have bounded retry policy.
* Camera card/storage errors are camera-side errors, not MediaStore errors.
* Firmware unsupported/untested should update compatibility diagnostics.
* User action required on camera must be explicit in UI.

## Media Transfer Errors

```text
Media.ObjectCountUnavailable
Media.ObjectHandlesUnavailable
Media.ObjectInfoUnavailable
Media.UnsupportedObjectFormat
Media.UnknownObjectFormat
Media.StaleObjectList
Media.InvalidFilename
Media.MetadataUnavailable
Thumbnail.NotAvailable
Thumbnail.UnsupportedFormat
Thumbnail.Timeout
Thumbnail.DecodeFailed
Transfer.ObjectNotFound
Transfer.PartialReadFailed
Transfer.SizeMismatch
Transfer.CameraDisconnected
Transfer.Timeout
Transfer.Cancelled
Transfer.UnsupportedFormat
Transfer.OutputWriteFailed
```

Guidance:

* Unsupported format is not a crash.
* Unknown format must be represented explicitly.
* User cancellation is not error.
* Transfer output write failure may originate from storage layer and should be mapped carefully.
* Private filenames and EXIF data must be redacted by default.

## Storage and MediaStore Errors

```text
Storage.MediaStoreCreateFailed
Storage.OutputFdOpenFailed
Storage.OutputFdInvalid
Storage.OutputWriteFailed
Storage.PendingItemFinalizeFailed
Storage.PendingItemDeleteFailed
Storage.InsufficientSpace
Storage.DuplicateDetected
Storage.DestinationUnavailable
Storage.PermissionDenied
Storage.UnknownMimeType
Storage.FilenameSanitizationFailed
```

Guidance:

* Storage errors are Android-side, not camera-side.
* Partial imports must not be exposed as complete media items.
* Duplicate detection must not delete user media automatically in v1.
* MediaStore failures must be recoverable or cleanup-aware.

## Location / GPS Geotagging Errors

GPS-to-camera EXIF geotagging is part of v1 scope, but only as an explicit, foreground, opt-in feature.

```text
Location.PermissionDenied
Location.ProviderUnavailable
Location.Timeout
Location.AccuracyInsufficient
Location.DisabledByUser
Location.BackgroundNotAllowed
Location.NoRecentFix
Location.RedactionRequired
Location.CloudUploadForbidden
Location.HistoryPersistenceForbidden
Camera.LocationSyncUnsupported
Camera.LocationSyncRejected
BleProtocol.LocationPayloadUnsupported
Bluetooth.LocationWriteTimeout
```

Guidance:

* Location errors apply only when user explicitly enables GPS/geotagging.
* Location permission denial must not block non-geotagging workflows.
* No background location in first implementation.
* No persistent GPS history by default.
* Precise coordinates are redacted by default.
* Permission denial must fail safely.

## User Cancellation

Cancellation is a first-class result.

```text
User.CancelledNetworkRequest
User.CancelledConnection
User.CancelledScan
User.CancelledTransfer
User.CancelledImport
User.CancelledRemoteOperation
User.CancelledDiagnosticsExport
```

Guidance:

* Cancellation is not an error.
* Cancellation should clean up resources.
* Cancellation should not show alarming error UI.
* Cancellation must be distinguishable from timeout or failure.
* Cancellation may still produce diagnostic events.

## Error Mapping

### Rust to Kotlin

Example mapping:

```text
Rust FujiTransportError::Timeout
  -> Transport.Timeout

Rust FujiProtocolError::UnexpectedPacket
  -> Protocol.UnexpectedPacket

Rust FujiTransferError::ObjectNotFound
  -> Transfer.ObjectNotFound

Rust FujiIoError::WriteFailed
  -> Storage.OutputWriteFailed or Transport.WriteFailed depending on fd role
```

Rules:

* Rust errors must be converted to stable Kotlin/domain error codes.
* Raw Rust debug strings must not become user-facing messages.
* Raw integer codes may be stored in diagnostics only if safe and documented.
* Avoid lossy mapping when layer matters.
* Preserve source layer and operation name.

### Android to Domain

Example mapping:

```text
Bluetooth scan permission denied
  -> Permission.BluetoothScanDenied

Network request user rejection
  -> Wifi.UserRejectedNetworkRequest

MediaStore pending item creation failure
  -> Storage.MediaStoreCreateFailed
```

### Domain to UI

Rules:

* UI receives safe domain errors.
* UI does not receive raw payloads.
* UI maps errors to localized messages later.
* UI should offer action when recoverable.
* UI should distinguish retry/reconnect/settings/open-permissions actions.

## User-Facing Messages

User-facing messages should:

* Be concise.
* Identify the failing step.
* Suggest the next action.
* Avoid raw technical payloads.
* Avoid blaming the camera incorrectly.
* Avoid exposing secrets.
* Distinguish "unsupported" from "failed".

Examples:

```text
Permission.BluetoothScanDenied:
  Frameport needs Bluetooth scanning permission to find nearby cameras.

Wifi.UserRejectedNetworkRequest:
  Camera Wi-Fi connection was cancelled. Start connection again to continue.

Wifi.SocketConnectTimeout:
  Frameport joined the camera Wi-Fi network but could not reach the camera endpoint.

Protocol.CameraBusy:
  The camera is busy. Wait a moment and try again.

Transfer.CameraDisconnected:
  The camera disconnected during transfer. Reconnect and retry the import.

Storage.InsufficientSpace:
  Not enough storage space to finish the import.

User.CancelledTransfer:
  Import cancelled.
```

Exact copy may be refined later, but the message must remain safe and actionable.

## Diagnostics

Every diagnostic error should include where safe:

```text
error code
layer
operation
recoverability
timestamp
session id
transport
camera model if known
firmware version if known
Android version
retry count
elapsed time
redacted context
```

Do not include by default:

```text
Wi-Fi passphrase
pairing secret
full serial number
full MAC address
precise GPS
private filenames
raw BLE payload
raw PTP packet
raw media bytes
raw EXIF dump
```

Example diagnostic event:

```text
ErrorEvent(
  code = Wifi.SocketConnectTimeout,
  layer = Wifi,
  operation = OpenCommandSocket,
  recoverability = RetryableAfterReconnect,
  elapsedMs = 10000,
  context = { transport = "wifi-ptp-ip", endpoint = "camera-local" }
)
```

## Retry and Recovery Policy

Retryable:

```text
Bluetooth.ScanTimeout
Bluetooth.GattConnectionFailed
Wifi.NetworkUnavailable
Wifi.SocketConnectTimeout
Transport.Timeout
Protocol.CameraBusy
Transfer.Timeout
```

Retryable after reconnect:

```text
Wifi.NetworkLost
Transport.ConnectionReset
Camera.Disconnected
Transfer.CameraDisconnected
Protocol.SessionClosed
```

User action required:

```text
Permission.*
Bluetooth.AdapterDisabled
Wifi.UserRejectedNetworkRequest
Camera.CardMissing
Camera.UserActionRequiredOnCamera
Storage.InsufficientSpace
Location.PermissionDenied
```

Not retryable / unsupported:

```text
Protocol.UnsupportedOperation
Protocol.UnsupportedFunctionMode
Camera.FirmwareUnsupported
Media.UnsupportedObjectFormat
Camera.LocationSyncUnsupported
```

Rules:

* Retries must be bounded.
* Retry delay/backoff must be documented per operation.
* User cancellation stops retries.
* Background retries require user-visible operation.
* Repeated failures should produce diagnostic summary.

## Testing Requirements

Unit tests:

```text
error category mapping tests
Rust-to-Kotlin error mapping tests
Android-to-domain error mapping tests
domain-to-UI message key tests
recoverability classification tests
redaction tests
retry policy tests
cancellation classification tests
```

Rust tests:

```text
transport timeout mapping
invalid packet length mapping
unexpected packet mapping
object not found mapping
output write failure mapping
panic boundary tests where feasible
```

Android tests:

```text
permission denial flows
BLE scan timeout
Wi-Fi network rejection
socket connect timeout
MediaStore create failure
transfer cancellation
diagnostic event generation
ViewModel state transitions for recoverable errors
```

Integration/manual tests:

```text
camera disconnected during transfer
camera busy during operation
network lost during PTP-IP session
user cancels import
storage full simulation where possible
unsupported format handling
```

## Security and Privacy Rules

```text
Never include secrets in errors.
Never include raw payloads in user-facing errors.
Never include precise GPS in errors.
Never include private filenames by default.
Never expose full serial numbers or MAC addresses by default.
Never upload diagnostics automatically in v1.
Never map unknown sensitive native data into UI strings.
```

Error reporting must follow:

```text
docs/security/diagnostics-redaction.md
docs/security/reverse-engineering-boundary.md
SECURITY.md
```

## Related Documents

```text
README.md
AGENTS.md
CONTRIBUTING.md
SECURITY.md
NOTICE
docs/adr/0001-android-rust-boundary.md
docs/adr/0002-wifi-socket-fd-handoff.md
docs/adr/0003-ble-client-abstraction.md
docs/adr/0004-media-import-pipeline.md
docs/adr/0005-no-cloud-v1.md
docs/adr/0006-no-firmware-update-v1.md
docs/security/reverse-engineering-boundary.md
docs/security/diagnostics-redaction.md
docs/protocol/compatibility-matrix.md
docs/protocol/wifi-ptp-ip.md
docs/protocol/bluetooth-le.md
docs/protocol/media-transfer.md
docs/product/feature-scope.md
```
