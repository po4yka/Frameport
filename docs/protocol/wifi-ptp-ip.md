# Wi-Fi PTP-IP Protocol

## Purpose

This document defines Frameport's Wi-Fi PTP-IP protocol understanding and implementation boundary. Wi-Fi PTP-IP is expected to be the first production transport for Frameport v1.

Primary v1 target:

```text
Manual Wi-Fi connection
Android Network-bound socket handoff
Rust PTP-IP session open
Camera identity read
Media object enumeration
Thumbnail fetch
Image import
Connection diagnostics
```

This is an engineering protocol document, not marketing copy and not a compatibility guarantee.

## Scope

Covered:

```text
camera-local Wi-Fi connection behavior
Android Network-bound socket setup
socket file descriptor handoff
Rust PTP-IP session ownership
command channel
event channel later
live-view / through-picture channel later
media object enumeration
thumbnail transfer
object transfer
remote shutter later
diagnostics and errors
compatibility validation
```

Not covered:

```text
Bluetooth LE handoff details
USB PTP details
MediaStore import finalization
cloud/account/server-linkage behavior
firmware update
vendor API integrations
analytics
iOS behavior
desktop behavior
```

Separate related documents:

```text
docs/protocol/bluetooth-le.md
docs/protocol/usb-ptp.md
docs/adr/0004-media-import-pipeline.md
docs/adr/0005-no-cloud-v1.md
docs/adr/0006-no-firmware-update-v1.md
```

## Architecture Overview

Frameport uses this split:

```text
Android/Kotlin:
  owns camera Wi-Fi network request
  owns Android Network lifecycle
  owns socket creation
  owns network-bound socket connection
  passes owned socket file descriptors to Rust

Rust:
  owns PTP-IP session
  owns packet parsing
  owns transaction sequencing
  owns media object operations
  owns protocol diagnostics
  owns typed protocol errors
```

```text
Camera Wi-Fi network
  ↓
Android Wi-Fi connector
  ↓
Android Network
  ↓
Network-bound socket(s)
  ↓
owned file descriptor handoff through JNI
  ↓
Rust PTP-IP transport
  ↓
Rust PTP session
  ↓
media enumeration / transfer / diagnostics
```

Rust must not assume camera-local addresses are reachable through the Android default network. Production Android code should use Android-created, network-bound sockets.

## Android/Rust Boundary

Android owns:

```text
WifiNetworkSpecifier or equivalent network request
NetworkCallback lifecycle
user approval/cancellation handling
socket creation
Network.bindSocket or equivalent routing
file descriptor duplication/ownership transfer
foreground service state if needed
user-visible connection state
permission errors
network availability errors
```

Rust owns:

```text
fd-backed stream wrapper
PTP-IP framing
PTP session state
transaction IDs
operation sequencing
response parsing
event parsing later
object enumeration
object info parsing
thumbnail transfer
object transfer
protocol timeouts
protocol retries where appropriate
typed protocol errors
protocol diagnostics
```

Explicitly forbidden:

```text
ViewModel directly opening sockets
ViewModel directly calling packet APIs
Compose UI directly calling native SDK
Rust directly owning Android Network APIs
Rust directly owning Android permissions
Kotlin reimplementing PTP packet logic in feature modules
```

## Connection Lifecycle

### Manual Wi-Fi v1 Flow

```text
1. User enables camera wireless/image transfer mode on camera.
2. Frameport asks Android to connect to the camera Wi-Fi network.
3. User approves the Android Wi-Fi connection request.
4. Android receives NetworkCallback.onAvailable.
5. Android opens a socket bound to that Network.
6. Android connects the socket to the camera command endpoint.
7. Android transfers owned command socket fd to Rust.
8. Rust initializes the PTP-IP transport.
9. Rust opens a PTP session.
10. Rust reads basic camera identity or device info where supported.
11. Frameport enters SessionReady or a typed failure state.
```

### BLE-assisted Future Flow

```text
1. BLE discovers camera.
2. BLE connects to camera GATT.
3. BLE reads camera identity.
4. BLE obtains or confirms Wi-Fi handoff data where supported.
5. Android starts Wi-Fi connector.
6. Android performs socket fd handoff.
7. Rust opens PTP-IP session.
```

BLE-assisted handoff is future/candidate scope, not required for the initial manual Wi-Fi spike.

### Session Close

Cleanup:

```text
1. stop active transfers
2. stop live-view/event channels if active
3. close PTP session if possible
4. close Rust-owned fds
5. release Android network callbacks
6. clear foreground service state if no longer needed
7. emit final diagnostic state
```

## Channel Model

Expected channels, described conservatively:

```text
command channel:
  required for v1
  used for PTP-IP session commands, object enumeration, transfer requests

event channel:
  later
  used for asynchronous camera events if supported and validated

live-view / through-picture channel:
  later
  used for continuous live-view frames if supported and validated
```

Exact port numbers, channel semantics, and model-specific behavior must be verified through project-owned hardware tests and sanitized protocol notes before being marked supported. Until verified in this repository, channel details are known from references and require validation.

| Channel | v1 status | Purpose | Validation requirement |
|---|---|---|---|
| Command | Required | Session open, device info, object enumeration, object transfer | Verify on target camera/firmware |
| Event | Later | Camera events and async state | Verify channel semantics and failure behavior |
| Live-view / through-picture | Later | Frame stream for live view | Verify frame format, buffer behavior, latency, and stop semantics |

## Session State Machine

Conceptual states:

```text
Idle
PreparingNetwork
WaitingForUserNetworkApproval
NetworkAvailable
OpeningCommandSocket
HandingOffSocketFd
OpeningTransport
OpeningPtpSession
ReadingCameraIdentity
SessionReady
BrowsingMedia
Transferring
RemoteControlLater
LiveViewLater
Recovering
Closing
Closed
Error
```

State meanings:

* `Idle`: no active camera session exists.
* `PreparingNetwork`: Android is preparing permissions and network request inputs.
* `WaitingForUserNetworkApproval`: Android is waiting for user approval or system network selection.
* `NetworkAvailable`: Android has received a camera `Network`.
* `OpeningCommandSocket`: Android is creating and connecting a network-bound socket.
* `HandingOffSocketFd`: Android is transferring an owned socket fd to Rust.
* `OpeningTransport`: Rust is wrapping the fd and preparing PTP-IP framing.
* `OpeningPtpSession`: Rust is performing session open behavior.
* `ReadingCameraIdentity`: Rust is reading basic camera/device information where supported.
* `SessionReady`: the command session is ready for supported operations.
* `BrowsingMedia`: media object enumeration or metadata reads are active.
* `Transferring`: object or thumbnail transfer is active.
* `RemoteControlLater`: reserved future state for remote-control operations.
* `LiveViewLater`: reserved future state for live-view operations.
* `Recovering`: the app is attempting bounded cleanup or recovery after a failure.
* `Closing`: session shutdown and resource cleanup are active.
* `Closed`: all owned session resources have been released.
* `Error`: the session has entered a typed failure state.

Cancellation points:

```text
before network request
during network request
after network available but before socket connect
during PTP-IP open
during media browsing
during transfer
during close/recovery
```

Every cancellation must clean up Android and Rust resources.

## Media Operations

Required v1 operations:

```text
read camera identity where possible
list media object handles
read object info
fetch thumbnail where supported
download object to Android-provided output fd
report transfer progress
support cancellation
return typed errors
```

Format scope:

```text
JPEG:
  v1 candidate, must be verified

RAF:
  v1 candidate for X-T5 if verified

HEIF:
  v1 candidate if camera and Android import path are verified

Video:
  later unless explicitly verified and accepted into scope

Unknown formats:
  list conservatively, do not import by default unless safe
```

MediaStore finalization is outside this document and belongs to `docs/adr/0004-media-import-pipeline.md`.

## Remote-Control Operations

Remote control is future scope.

Candidate later operations:

```text
remote shutter
basic camera state read
limited property read/write
capture result diagnostics
```

Remote-control operations must not be added until command session stability and error handling are reliable.

Remote-control requirements:

```text
camera busy handling
bounded retries
clear user-visible state
no silent camera configuration changes
compatibility per camera/firmware
```

## Live-View Operations

Live view is future scope.

Candidate later operations:

```text
open live-view channel
receive frames
parse frame metadata
deliver compressed frame data to Android UI layer
drop stale frames
stop cleanly
recover after disconnect
```

Performance rules:

```text
no allocation per frame in hot path
bounded buffers
latest-frame-wins option
no raw frame dumps in release diagnostics
typed errors for frame/channel failures
```

Live view requires a separate implementation document or ADR update before production work.

## Error Model

Android/network errors:

```text
PermissionDenied.NearbyWifiDevices
Wifi.UserRejected
Wifi.NetworkUnavailable
Wifi.NetworkLost
Wifi.SocketBindFailed
Wifi.SocketConnectTimeout
Wifi.NoRouteToCamera
Wifi.CallbackTimeout
```

Rust transport errors:

```text
Transport.ReadFailed
Transport.WriteFailed
Transport.ConnectionReset
Transport.Timeout
Transport.InvalidFd
Transport.SessionClosed
```

Protocol errors:

```text
Protocol.HandshakeRejected
Protocol.OpenSessionFailed
Protocol.UnexpectedPacket
Protocol.InvalidPacketLength
Protocol.UnsupportedOperation
Protocol.UnsupportedFunctionMode
Protocol.CameraBusy
Protocol.Timeout
Protocol.SessionClosed
```

Transfer errors:

```text
Transfer.ObjectNotFound
Transfer.ObjectInfoUnavailable
Transfer.PartialReadFailed
Transfer.SizeMismatch
Transfer.Cancelled
Transfer.OutputWriteFailed
```

Diagnostics must distinguish:

```text
Android permission failure
Android Wi-Fi/network failure
socket/fd handoff failure
Rust transport failure
PTP/protocol failure
camera busy/unsupported state
storage/output fd failure
user cancellation
```

## Diagnostics

Allowed by default:

```text
app version
Android version
Android device model
camera model if known
firmware version if known
transport type
connection state
operation name
typed error code
retry count
elapsed time
packet length category
object count
transfer progress percentage
```

Redacted by default:

```text
Wi-Fi passphrase
SSID if personal
BSSID/MAC address
full camera serial number
raw packet bytes
raw object data
private filenames
EXIF GPS
precise location
```

Diagnostic timeline example:

```text
NetworkRequestStarted
NetworkAvailable
CommandSocketBound
CommandSocketConnected
FdHandoffSucceeded
TransportOpenStarted
PtpSessionOpened
CameraIdentityRead
MediaListStarted
MediaListSucceeded
```

Failure timeline example:

```text
NetworkAvailable
CommandSocketBound
CommandSocketConnectTimeout
Error(Wifi.SocketConnectTimeout)
CleanupStarted
CleanupCompleted
```

## Compatibility and Validation

Wi-Fi PTP-IP support must be claimed per:

```text
camera model
camera firmware version
Android device model
Android OS version
Frameport version/commit
feature
transport path
```

A "supported" claim requires:

```text
real hardware test
feature-specific pass result
redacted diagnostic evidence
repeatable behavior
compatibility matrix update
```

Do not claim broad support from static analysis, open-source references, or behavior on another model.

Initial primary target:

```text
Fujifilm X-T5
```

Support must remain feature-by-feature and firmware-specific.

## Known Unknowns

Current unknowns:

```text
exact behavior by camera firmware version
whether all cameras expose the same PTP-IP setup sequence
whether command/event/live-view channel semantics vary by model
whether object enumeration differs by storage/card configuration
whether RAF and HEIF transfer behavior differs by model/settings
whether camera busy responses require model-specific retry policy
whether live-view frame format varies by model
whether Android OEM network routing affects socket handoff reliability
whether reconnect behavior differs after camera sleep/off states
```

Unknowns must be resolved with project-owned tests, sanitized traces, and compatibility matrix updates.

## Security and Privacy Rules

```text
Do not log Wi-Fi passphrases.
Do not log full camera serial numbers.
Do not log MAC addresses by default.
Do not log raw packet bytes in release builds.
Do not export raw packet captures by default.
Do not send camera-local data to cloud in v1.
Do not add vendor cloud endpoints.
Do not add firmware update behavior.
Do not add hidden background network behavior.
```

Packet captures and protocol traces must follow `docs/security/diagnostics-redaction.md` and `docs/security/reverse-engineering-boundary.md`.

## Testing Requirements

Rust unit tests:

```text
PTP packet encode/decode
response parsing
event parsing later
object info parsing
invalid packet length handling
transport timeout mapping
session state transitions
```

Rust fake camera tests:

```text
successful session open
handshake rejection
unexpected packet
object list success
object list failure
thumbnail success/failure
download success
download cancellation
connection reset during transfer
```

Android tests:

```text
fake Wi-Fi connector state transitions
socket handoff wrapper behavior
ViewModel connection states
permission denial flow
network user rejection flow
cleanup on cancellation
diagnostic event mapping
```

Manual hardware tests:

```text
manual Wi-Fi connect to X-T5
PTP-IP session open
camera identity read
object enumeration
thumbnail fetch
JPEG import
RAF import if supported
HEIF import if supported
disconnect during transfer
repeat connect/disconnect
Android device/OEM matrix
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
docs/protocol/error-model.md
docs/product/feature-scope.md
```
