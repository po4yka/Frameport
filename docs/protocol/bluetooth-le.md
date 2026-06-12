# Bluetooth LE Protocol

## Purpose

This document defines Frameport's Bluetooth LE protocol understanding and implementation boundary.

BLE is expected to be used for:

```text
camera discovery
camera identity read
pairing or registration state
Wi-Fi handoff data where supported
camera state notifications later
remote shutter later
GPS-to-camera EXIF geotagging
```

BLE is not expected to carry:

```text
full image transfer
RAF/JPEG/HEIF object transfer
video transfer
live-view frame stream
firmware update in v1
cloud/account sync
```

BLE is a bootstrap/control channel, while bulk media transfer belongs to Wi-Fi PTP-IP or future USB PTP. This is an engineering protocol document, not a compatibility guarantee.

## Scope

Covered:

```text
Android BLE scan flow
GATT connection lifecycle
service discovery
characteristic read/write model
notification subscription model
operation queue requirements
BLE identity metadata
Wi-Fi handoff data
future BLE remote shutter
GPS-to-camera EXIF geotagging
BLE diagnostics
compatibility validation
redaction rules
```

Not covered:

```text
Wi-Fi PTP-IP packet/session details
USB PTP details
MediaStore import pipeline
cloud/account behavior
firmware update
vendor server-linkage
iOS behavior
desktop behavior
```

Separate related documents:

```text
docs/protocol/wifi-ptp-ip.md
docs/protocol/usb-ptp.md
docs/adr/0004-media-import-pipeline.md
docs/adr/0005-no-cloud-v1.md
docs/adr/0006-no-firmware-update-v1.md
```

## Architecture Overview

Frameport uses this split:

```text
Android/Kotlin:
  owns BLE scan
  owns BluetoothGatt lifecycle
  owns service discovery
  owns characteristic reads/writes
  owns notification subscriptions
  owns operation queue
  owns permission and foreground-service behavior
  owns user-visible BLE state

Rust:
  owns Fujifilm-specific payload interpretation
  owns BLE command payload generation
  owns pairing/handoff protocol state
  owns typed BLE protocol errors
  owns protocol diagnostics independent of Android GATT
```

```text
Android BLE scan
  ↓
camera advertisement
  ↓
FujiBleClient
  ↓
BluetoothGatt connection
  ↓
service discovery
  ↓
characteristic read/write/notify
  ↓ raw bytes
Rust BLE protocol layer
  ├── advertisement parser
  ├── characteristic payload parser
  ├── command builder
  ├── pairing/handoff state
  └── typed diagnostics
```

Rust must not directly own Android `BluetoothGatt`, `BluetoothDevice`, `BluetoothLeScanner`, permission prompts, foreground service behavior, or Android lifecycle. Feature/UI modules must not access Android BLE APIs directly.

## Android/Rust Boundary

Android owns:

```text
BLUETOOTH_SCAN permission handling
BLUETOOTH_CONNECT permission handling
location permission only if a feature truly requires it
Bluetooth adapter state
bounded scan lifecycle
scan filters
GATT connection and disconnect
service discovery
MTU negotiation if needed
read/write operation queue
notification subscription
GATT timeout handling
GATT reconnect policy
foreground service lifecycle for active camera operations
user-visible permission denial state
```

Rust owns:

```text
advertisement payload interpretation where useful
GATT payload parsing
payload length validation
command payload construction
pairing-state interpretation
Wi-Fi credential payload interpretation
remote shutter payload construction later
GPS/location payload construction for explicit geotagging when supported
typed BLE protocol errors
protocol-level diagnostics
```

Explicitly forbidden:

```text
ViewModel directly using BluetoothGatt
Composable directly starting BLE scan
feature module depending on concrete BLE implementation
Rust storing Android Bluetooth objects
Rust requesting Android permissions
Rust owning foreground service lifecycle
Kotlin feature modules parsing Fujifilm BLE payloads ad hoc
```

## BLE Lifecycle

### Discovery Flow

```text
1. User opens camera connection screen.
2. App checks Bluetooth availability and permissions.
3. App starts a bounded BLE scan.
4. Scan results are filtered for camera candidates.
5. User selects a camera.
6. Scan stops.
7. App transitions to GATT connection flow.
```

### Connection Flow

```text
1. Android connects to the selected device GATT server.
2. App waits for connection callback.
3. App discovers services.
4. App verifies required service/characteristic presence.
5. App reads camera identity where supported.
6. App performs pairing/registration state checks where supported.
7. App enters BLE-ready state or typed failure state.
```

### Handoff Flow Later

```text
1. BLE-ready state is reached.
2. App reads or confirms Wi-Fi handoff data where supported.
3. Rust parses and validates handoff payloads.
4. Android Wi-Fi connector receives sanitized credentials.
5. Android requests camera Wi-Fi network.
6. Wi-Fi socket fd handoff starts.
7. Rust PTP-IP session starts.
```

### Disconnect Flow

```text
1. Cancel pending GATT operation.
2. Stop notifications.
3. Close GATT connection.
4. Clear active BLE state.
5. Stop foreground service if no longer needed.
6. Emit final diagnostic state.
```

## GATT Operation Model

Rules:

```text
Only one GATT operation may be in flight at a time.
Every read must have a timeout.
Every write must have a timeout.
Every notification subscription must have a success/failure state.
Pending operations must be cancelled on disconnect.
Disconnect must be idempotent.
Service discovery must complete before characteristic access.
Characteristic lookup must return typed errors.
Notifications must stop when the connection closes.
Operation queue state must be visible to diagnostics.
```

Operation categories:

```text
scan
connect
discover services
request MTU
read characteristic
write characteristic
subscribe notification
unsubscribe notification
disconnect
```

Conceptual operation queue:

```text
Idle
  ↓ enqueue operation
Running(operation)
  ├── Success → Idle
  ├── Timeout → Error/Recovering
  ├── Disconnect → Cancelled
  └── Cancel → Cancelled
```

BLE operation serialization is mandatory because Android GATT behavior is unreliable when multiple operations are issued concurrently.

## Camera Discovery

Discovery should:

```text
use bounded scan windows
filter to camera-relevant candidates
avoid showing unrelated BLE devices
avoid deriving user location from scan results
avoid persistent scanning unless user explicitly starts it
stop scan when leaving connection screen
stop scan when camera is selected
```

Diagnostic fields allowed by default:

```text
scan started
scan stopped
scan duration
candidate count
camera model hint if known
RSSI bucket, not exact RSSI if privacy policy chooses
typed scan error code
```

Redacted by default:

```text
raw advertisement payload
full device address
nearby unrelated device data
full camera serial number
full MAC address
```

## Camera Identity

Camera identity may include:

```text
camera model
firmware version if exposed
serial number or serial-like identifier
BLE protocol version
hardware/software revision
camera state flags
```

Rules:

```text
camera model may be displayed
firmware version may be displayed
full serial number must be redacted by default
full MAC address must be redacted by default
identity fields must be treated as untrusted input
identity parsing must fail safely
identity availability varies by camera/firmware
```

Identity read success does not imply full feature compatibility.

## Pairing and Registration

Pairing/registration is a protocol state, not necessarily an Android bonding assumption. Camera companion BLE flows may include camera-specific pairing, registration, or trust state independent from Android Bluetooth bonding.

Rules:

```text
pairing secrets must never be logged
pairing secrets must never be exported in diagnostics
pairing state must be represented as typed state
failed pairing must be recoverable
re-pairing must be explicit and user-visible
stale pairing state must be diagnosable
```

Possible states:

```text
Pairing.Unknown
Pairing.NotRequired
Pairing.Required
Pairing.InProgress
Pairing.Accepted
Pairing.Rejected
Pairing.Stale
Pairing.Unsupported
```

Do not claim exact pairing behavior until verified per camera/firmware.

## Wi-Fi Handoff

BLE handoff may provide:

```text
camera Wi-Fi SSID
camera Wi-Fi passphrase
camera network state
camera AP launch/control state
handoff readiness state
```

Rules:

```text
Wi-Fi passphrase is highly sensitive.
Wi-Fi passphrase must never be logged.
Wi-Fi passphrase must never be exported.
Wi-Fi SSID must be redacted if personal or unknown.
Handoff data must be passed only to Android Wi-Fi connector.
Rust may parse handoff payloads but must not log secrets.
Android owns Wi-Fi network request and socket binding.
```

Handoff lifecycle:

```text
BLEReady
  ↓
ReadHandoffData
  ↓
ValidateHandoffData
  ↓
StartAndroidWifiConnector
  ↓
NetworkAvailable
  ↓
SocketFdHandoff
  ↓
RustPtpIpSession
```

Failure categories:

```text
BleProtocol.MissingWifiCredentials
BleProtocol.MalformedHandoffPayload
BleProtocol.CameraNotReadyForHandoff
Bluetooth.ReadTimeout
Wifi.UserRejected
Wifi.NetworkUnavailable
Wifi.SocketConnectTimeout
```

## Remote Shutter

Remote shutter is later scope.

Candidate BLE shutter states:

```text
RemoteShutter.Unsupported
RemoteShutter.Ready
RemoteShutter.HalfPress
RemoteShutter.FullPress
RemoteShutter.Released
RemoteShutter.CameraBusy
RemoteShutter.Failed
```

Rules:

```text
remote shutter must be explicit user action
no hidden capture
no background shutter trigger
camera busy must be user-visible
operation must be cancellable where possible
compatibility must be verified per camera/firmware
```

Do not include exact characteristic UUIDs or payload values unless already present in project-owned, sanitized protocol docs and safe to include. If exact values are not verified by the project, describe them as unknown/verification-required.

## Location / GPS-to-Camera EXIF Geotagging

GPS-to-camera EXIF geotagging is part of v1 scope, but support is compatibility-gated and must be verified per camera/firmware.

Feature definition:

```text
Frameport may send the Android phone's current GPS coordinates to the connected camera so that the camera can embed location metadata into photo EXIF during shooting, if supported by the camera and firmware.
```

Rules:

```text
opt-in only
foreground-only in v1
explicit location permission rationale
minimum required location permission
user-visible active geotagging state
stop/pause control
no background location in first implementation
no persistent GPS history by default
no cloud upload of location data
precise coordinates redacted from diagnostics by default
no raw coordinates in release logs
compatibility must be verified per camera/firmware
```

This feature requires:

```text
explicit user opt-in
foreground-only active state
Android location permission
BLE/control-channel validation
successful test capture with EXIF GPS verification
```

Failure categories:

```text
PermissionDenied.Location
Location.ProviderUnavailable
Location.Timeout
Location.AccuracyInsufficient
BleProtocol.LocationPayloadUnsupported
Bluetooth.WriteTimeout
Camera.LocationSyncUnsupported
Camera.LocationSyncRejected
```

BLE connection success does not imply GPS-to-camera EXIF geotagging support.

## Error Model

Permission failures:

```text
PermissionDenied.BluetoothScan
PermissionDenied.BluetoothConnect
PermissionDenied.LocationIfRequired
PermissionDenied.NotificationsIfForegroundServiceRequired
```

Adapter/scan failures:

```text
Bluetooth.AdapterUnavailable
Bluetooth.AdapterDisabled
Bluetooth.ScanFailed
Bluetooth.ScanTimeout
Bluetooth.NoCameraFound
Bluetooth.UnsupportedDevice
```

Connection failures:

```text
Bluetooth.GattConnectionFailed
Bluetooth.GattDisconnected
Bluetooth.GattTimeout
Bluetooth.MtuRequestFailed
Bluetooth.ServiceDiscoveryFailed
Bluetooth.ServiceNotFound
Bluetooth.CharacteristicNotFound
```

Operation failures:

```text
Bluetooth.ReadTimeout
Bluetooth.WriteTimeout
Bluetooth.NotificationSubscribeFailed
Bluetooth.NotificationStreamClosed
Bluetooth.OperationCancelled
Bluetooth.OperationQueueClosed
Bluetooth.UnexpectedCallback
```

Protocol failures:

```text
BleProtocol.MalformedPayload
BleProtocol.InvalidPayloadLength
BleProtocol.UnsupportedCameraState
BleProtocol.PairingRejected
BleProtocol.PairingRequired
BleProtocol.MissingWifiCredentials
BleProtocol.UnsupportedCharacteristicPayload
BleProtocol.LocationPayloadUnsupported
```

Diagnostics must distinguish Android BLE transport failures from Fujifilm BLE payload/protocol failures.

## Diagnostics

Allowed by default:

```text
app version
Android version
Android device model
camera model if known
firmware version if known
BLE state
scan duration
candidate count
operation name
characteristic category
service category
typed error code
retry count
elapsed time
handoff state
```

Redacted by default:

```text
raw advertisement payload
raw characteristic payload
camera Wi-Fi passphrase
pairing secret
full serial number
full MAC address
precise GPS coordinates
location history
private filenames
```

Diagnostic timeline example:

```text
BlePermissionGranted
ScanStarted
CameraCandidateFound
ScanStopped
GattConnecting
GattConnected
ServicesDiscovered
CameraIdentityRead
BleReady
```

Failure timeline example:

```text
ScanStarted
ScanTimeout
Error(Bluetooth.NoCameraFound)
CleanupCompleted
```

Handoff timeline example:

```text
BleReady
HandoffReadStarted
HandoffReadSucceeded
WifiConnectorStarted
```

## Compatibility and Validation

BLE support must be claimed per:

```text
camera model
camera firmware version
Android device model
Android OS version
Frameport version/commit
feature
BLE operation
```

A "supported" claim requires:

```text
real hardware test
feature-specific pass result
redacted diagnostic evidence
repeatable behavior
compatibility matrix update
```

Do not claim:

```text
BLE scan support implies Wi-Fi handoff support
BLE connect support implies remote shutter support
BLE connect support implies GPS geotagging support
location payload send success implies EXIF GPS metadata was embedded
support on one firmware implies support on all firmware
support on one camera implies support on all Fujifilm cameras
```

Initial primary target:

```text
Fujifilm X-T5
```

Support must remain feature-by-feature and firmware-specific.

## Known Unknowns

Current unknowns:

```text
exact BLE service/characteristic behavior by camera model
exact BLE behavior by firmware version
whether RED-compliant or region/model variants affect service maps
whether pairing state differs across cameras
whether Wi-Fi handoff payload structure differs across cameras
whether remote shutter payload behavior differs across cameras
whether GPS/location sync payload behavior differs across cameras
whether Android OEM BLE stacks affect reconnect reliability
whether camera sleep/off state changes notification behavior
whether GATT operation timing needs model-specific backoff
```

Unknowns must be resolved with project-owned tests, sanitized traces, and compatibility matrix updates.

## Security and Privacy Rules

```text
Do not log Wi-Fi passphrases.
Do not log pairing secrets.
Do not log raw BLE payloads in release builds.
Do not log full serial numbers.
Do not log full MAC addresses.
Do not log precise GPS coordinates.
Do not derive location from BLE scan results.
Do not keep background BLE running without explicit user-visible operation.
Do not add background location for BLE/geotagging without separate ADR/security review.
Do not send BLE-derived data to cloud in v1.
```

BLE diagnostics and packet/payload traces must follow:

```text
docs/security/diagnostics-redaction.md
docs/security/reverse-engineering-boundary.md
```

## Testing Requirements

Android unit/fake tests:

```text
fake FujiBleClient scan results
permission denied flows
scan timeout behavior
adapter disabled behavior
GATT connection state transitions
operation queue serialization
read timeout behavior
write timeout behavior
notification cancellation behavior
disconnect cleanup
ViewModel state transitions
```

Android manual/instrumentation tests:

```text
bounded scan starts/stops
camera appears in scan results
user denies Bluetooth permission
Bluetooth adapter disabled
GATT connect/disconnect
service discovery failure
characteristic missing
repeat connect/disconnect
foreground/background transition
foreground service notification behavior when active
```

Rust tests:

```text
advertisement parser fixtures
identity payload parser fixtures
handoff payload parser fixtures
malformed payload tests
invalid payload length tests
command payload generation tests
pairing state machine tests
remote shutter payload tests later
location payload tests when geotagging support is implemented for a target camera
```

Hardware tests:

```text
Fujifilm X-T5 current firmware
Pixel device
Samsung device later
Xiaomi device later
Android version matrix
BLE scan reliability
GATT connect reliability
identity read
Wi-Fi handoff data read
remote shutter later
GPS-to-camera EXIF geotagging
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
docs/protocol/error-model.md
docs/product/feature-scope.md
```
