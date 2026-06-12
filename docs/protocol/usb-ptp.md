# USB PTP Protocol

## Purpose

This document defines Frameport's future USB PTP transport scope.

Feature definition:

```text
Frameport may later use Android USB host mode to communicate with a connected camera over PTP, allowing wired camera discovery, media enumeration, and media import into Android MediaStore where supported.
```

USB mode is separate from:

```text
BLE discovery and Wi-Fi handoff
Wi-Fi PTP-IP
MediaStore finalization
firmware update
cloud/account behavior
```

This document is an engineering/protocol document, not a compatibility guarantee.

## Scope

Covered:

```text
Android USB host detection
USB permission request
USB device open lifecycle
raw descriptor access where needed
USB file descriptor handoff
bulk endpoint identification
PTP-over-USB session behavior
object enumeration
thumbnail transfer
object transfer
transfer cancellation
diagnostics
compatibility validation
```

Not covered:

```text
Wi-Fi PTP-IP session details
Bluetooth LE handoff
GPS-to-camera EXIF geotagging
MediaStore finalization details
firmware update
firmware transfer
vendor SDK binary integration
cloud/account sync
analytics
iOS behavior
desktop behavior
```

Related documents:

```text
docs/protocol/wifi-ptp-ip.md
docs/protocol/bluetooth-le.md
docs/protocol/media-transfer.md
docs/adr/0004-media-import-pipeline.md
docs/adr/0006-no-firmware-update-v1.md
```

## Product Status

```text
Status:
  Future / later scope

USB PTP is not the first production transport. Frameport v1 starts with Wi-Fi PTP-IP import and local diagnostics unless the product scope document explicitly says otherwise.
```

Allowed later:

```text
wired camera discovery
wired PTP session open
wired media object enumeration
wired import into MediaStore
wired diagnostics
```

Not allowed in v1 through USB:

```text
firmware update
firmware transfer
firmware flashing
proprietary SDK binary dependency without ADR
hidden background USB behavior
claiming USB support without hardware validation
```

## Architecture Overview

Frameport uses this split:

```text
Android/Kotlin:
  owns USB attach/detach detection
  owns UsbManager permission flow
  owns UsbDevice open lifecycle
  owns raw descriptor retrieval if needed
  owns Android-side fd acquisition
  owns user-visible USB state
  owns MediaStore output fd creation for imports

Rust:
  owns PTP-over-USB transport behavior
  owns descriptor interpretation where needed
  owns endpoint-level PTP transaction behavior where applicable
  owns object enumeration
  owns object transfer
  owns typed USB/PTP errors
  owns protocol diagnostics
```

```text
Camera connected over USB
  ↓
Android UsbManager / receiver
  ↓
User grants USB permission
  ↓
Android opens UsbDeviceConnection
  ↓
Android obtains fd and descriptors
  ↓
owned fd / descriptor handoff through JNI
  ↓
Rust USB PTP transport
  ↓
PTP session
  ↓
media enumeration / transfer / diagnostics
```

Rust must not request Android USB permission. Rust must not own Android `UsbManager`. Android must not implement PTP packet logic in UI, ViewModels, or platform screens.

## Android/Rust Boundary

Android owns:

```text
USB attach/detach events
UsbManager access
permission request
permission denial handling
UsbDevice selection
UsbInterface inspection if needed
UsbDeviceConnection opening
file descriptor extraction
raw descriptor extraction if needed
fd duplication/ownership handoff
foreground/user-visible state if needed
MediaStore output fd creation for imports
```

Rust owns:

```text
fd-backed USB transport wrapper
PTP-over-USB transaction model
bulk read/write behavior where applicable
PTP session state
object enumeration
object metadata parsing
thumbnail transfer
full object transfer
protocol retries/timeouts
typed errors
protocol diagnostics
```

Explicitly forbidden:

```text
Composable directly accessing UsbManager
ViewModel directly opening UsbDeviceConnection
feature module directly calling JNI
Rust directly requesting Android USB permissions
Rust writing shared storage paths
Rust implementing firmware update over USB in v1/later docs without separate ADR
```

## USB Connection Lifecycle

Conceptual lifecycle:

```text
Disconnected
  ↓ camera attached
DeviceDetected
  ↓ permission request
PermissionPending
  ├── denied → PermissionDenied
  └── granted → OpeningDevice

OpeningDevice
  ├── success → DeviceOpen
  └── failure → Error(Usb.OpenFailed)

DeviceOpen
  ↓ fd/descriptors prepared
FdHandoff
  ↓
RustTransportOpen
  ↓
PtpSessionOpening
  ├── success → UsbSessionReady
  └── failure → Error(Protocol.OpenSessionFailed)

UsbSessionReady
  ├── MediaBrowsing
  ├── Transferring
  └── Closing

Closing
  ↓
Closed
```

Cleanup requirements:

```text
cancel active transfer
close Rust-owned fd
close Android UsbDeviceConnection if Android owns it
release any callbacks/receivers if session-scoped
emit final diagnostic state
```

## USB Device Discovery

Expected behavior:

```text
Android detects USB attach event or scans currently attached devices.
App filters candidate devices conservatively.
User is shown a clear USB connection state.
App asks for USB permission only when needed.
```

Rules:

```text
do not claim every USB camera is supported
do not assume every Fujifilm USB mode is PTP mode
handle cameras in non-PTP modes safely
handle detach during permission request
handle detach during transfer
```

Some cameras may expose USB modes that are not suitable for PTP media transfer. Frameport must detect unsupported modes and fail safely.

Do not include proprietary vendor mode details unless already verified in project-owned docs.

## Permission Model

Rules:

```text
USB permission must be explicit and user-visible.
Permission denial must not crash the app.
Permission denial must not affect Wi-Fi/BLE workflows.
Permission grant is scoped to the Android USB device/session.
The app must handle device detach after permission grant.
The app must not keep hidden USB background behavior.
```

Error states:

```text
PermissionDenied.Usb
Usb.PermissionRequestFailed
Usb.DeviceDetachedBeforePermission
Usb.PermissionRevoked
```

## Descriptor and Endpoint Model

Expected descriptor-related tasks:

```text
identify camera USB device
inspect interfaces
identify PTP-compatible interface if available
identify bulk IN endpoint
identify bulk OUT endpoint
identify interrupt/event endpoint if available
reject unsupported interface layouts
```

Rules:

```text
descriptors are untrusted input
descriptor parsing must validate lengths
do not log raw descriptors by default
do not export raw descriptors by default
redact stable hardware identifiers
preserve enough category-level information for diagnostics
```

Allowed diagnostic descriptor fields:

```text
interface count
endpoint count
endpoint direction category
endpoint transfer type category
PTP-compatible interface found/not found
typed descriptor error
```

Redacted by default:

```text
full serial number
full hardware identifier
raw descriptor bytes
private device identifiers
```

## File Descriptor Handoff

Production flow:

```text
Android opens USB device after permission.
Android obtains a file descriptor or transport handle.
Android duplicates/transfers an owned fd to Rust.
Rust wraps the owned fd.
Rust closes the owned fd when the session ends.
Android closes Android-owned handles according to ownership rules.
```

Ownership rules:

```text
Every fd crossing JNI must have documented ownership.
If Rust owns fd, Rust closes fd.
If Android keeps the original object, Android must pass a duplicated fd.
Rust must not close Android-owned fd.
Android must not close Rust-owned fd while Rust is active.
Cancellation must close active resources deterministically.
Double-close must be prevented.
```

Error states:

```text
Usb.FdUnavailable
Usb.FdDupFailed
Usb.FdOwnershipViolation
Usb.FdClosedDuringOperation
Usb.DeviceConnectionClosed
```

## PTP-over-USB Session Model

Expected phases:

```text
transport open
device info read
session open if required
object count / handle enumeration
object info read
thumbnail read where available
object transfer
session close
transport close
```

Rules:

```text
Rust owns PTP transaction sequencing.
Rust validates response lengths.
Rust maps protocol responses into typed errors.
Rust handles camera busy/unsupported states.
Rust does not expose raw PTP packet APIs to Kotlin.
Rust does not implement firmware update commands in this document.
```

PTP transaction phases:

```text
command phase
optional data phase
response phase
optional event phase later
```

Exact operation support must be verified per camera/firmware.

## Media Operations

USB media operations later may include:

```text
camera identity read
object enumeration
object metadata read
thumbnail fetch
single object import
batch object import
JPEG import
RAF import if verified
HEIF import if verified
video import later if accepted
```

MediaStore finalization is Android-owned and documented in the media import pipeline ADR. The media transfer protocol behavior should align with `docs/protocol/media-transfer.md`. USB support for any format must be validated separately from Wi-Fi support.

Do not claim USB support based on Wi-Fi support.

## Error Model

Permission errors:

```text
PermissionDenied.Usb
Usb.PermissionRequestFailed
Usb.PermissionRevoked
```

Device lifecycle errors:

```text
Usb.DeviceNotFound
Usb.DeviceDetached
Usb.DeviceOpenFailed
Usb.UnsupportedDevice
Usb.UnsupportedMode
Usb.UnsupportedInterface
Usb.EndpointNotFound
Usb.DescriptorInvalid
```

File descriptor errors:

```text
Usb.FdUnavailable
Usb.FdDupFailed
Usb.FdOwnershipViolation
Usb.DeviceConnectionClosed
```

Transport errors:

```text
Usb.BulkReadFailed
Usb.BulkWriteFailed
Usb.TransferTimeout
Usb.ConnectionReset
Usb.UnexpectedEndpointState
```

Protocol errors:

```text
Protocol.OpenSessionFailed
Protocol.UnexpectedPacket
Protocol.InvalidPacketLength
Protocol.UnsupportedOperation
Protocol.CameraBusy
Protocol.SessionClosed
```

Transfer errors:

```text
Transfer.ObjectNotFound
Transfer.PartialReadFailed
Transfer.SizeMismatch
Transfer.CameraDisconnected
Transfer.Cancelled
Transfer.OutputWriteFailed
```

Diagnostics must distinguish:

```text
USB permission failure
USB device detach
USB descriptor/interface failure
fd handoff failure
USB transport failure
PTP protocol failure
media transfer failure
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
USB state
permission state
interface category
endpoint category
operation name
typed error code
retry count
elapsed time
object count
format category
transfer progress percentage
```

Redacted by default:

```text
full camera serial number
full USB hardware identifier
raw USB descriptors
raw PTP packets
raw media bytes
private filenames
full filesystem paths
EXIF GPS
precise location
```

Diagnostic timeline example:

```text
UsbDeviceDetected
UsbPermissionRequested
UsbPermissionGranted
UsbDeviceOpened
UsbFdHandoffSucceeded
UsbTransportOpenStarted
PtpSessionOpened
MediaEnumerationStarted
```

Failure timeline example:

```text
UsbDeviceDetected
UsbPermissionGranted
UsbDeviceDetached
Error(Usb.DeviceDetached)
CleanupCompleted
```

## Compatibility and Validation

USB support must be claimed per:

```text
camera model
camera firmware version
camera USB mode
Android device model
Android OS version
Frameport version/commit
feature
media format
```

A "supported" claim requires:

```text
real hardware test
feature-specific pass result
successful USB permission/open
successful PTP session
successful feature operation
redacted diagnostic evidence
compatibility matrix update
repeatable behavior
```

Do not claim:

```text
Wi-Fi support implies USB support
USB device detected implies PTP support
PTP session support implies import support
JPEG import support implies RAF/HEIF support
support on one firmware implies support on all firmware
support on one camera implies support on all Fujifilm cameras
```

Initial primary camera target remains:

```text
Fujifilm X-T5
```

USB support must remain later-scope and feature-by-feature.

## Known Unknowns

Current unknowns:

```text
which camera USB modes expose usable PTP media transfer
whether USB behavior differs by firmware version
whether all target cameras expose compatible interfaces/endpoints
whether object enumeration matches Wi-Fi behavior
whether RAF/HEIF transfer behavior differs over USB
whether video transfer requires separate handling
whether camera busy behavior differs over USB
whether Android OEM USB host behavior affects reliability
whether USB-C hubs/adapters affect stability
whether detach events during transfer are recoverable
```

Unknowns must be resolved with project-owned hardware tests, sanitized diagnostics, and compatibility matrix updates.

## Security and Privacy Rules

```text
Do not log raw USB descriptors by default.
Do not log full serial numbers.
Do not log full hardware identifiers.
Do not log raw PTP packets in release builds.
Do not log raw media bytes.
Do not include real user media in tests.
Do not implement firmware update through USB without separate ADR.
Do not keep hidden background USB behavior.
Do not claim USB support without hardware validation.
```

USB diagnostics and protocol traces must follow:

```text
docs/security/diagnostics-redaction.md
docs/security/reverse-engineering-boundary.md
```

## Testing Requirements

Android unit/fake tests:

```text
fake USB device detection
permission denied flow
permission granted flow
device detached before permission
device detached after permission
device open failure
fd handoff error mapping
ViewModel state transitions
```

Android instrumentation/manual tests:

```text
USB permission prompt
USB attach/detach
device open/close
detach during idle session
detach during transfer
foreground/background behavior
USB hub/adapter behavior where practical
```

Rust tests:

```text
descriptor parser tests with synthetic descriptors
endpoint selection tests
PTP transaction state tests
invalid descriptor length tests
bulk read/write error mapping
session open failure tests
object enumeration tests with fake transport
transfer cancellation tests
```

Hardware tests:

```text
Fujifilm X-T5 current firmware
Android device USB host support
USB-C cable and adapter matrix
USB permission/open
PTP session open
object enumeration
JPEG import
RAF import if supported
HEIF import if supported
disconnect during transfer
repeat connect/disconnect
```

Privacy tests:

```text
no raw descriptors in release diagnostics
no full hardware identifiers in logs
no raw PTP packets in release diagnostics
no raw media bytes in diagnostics
```

## Related Documents

```text
README.md
AGENTS.md
CONTRIBUTING.md
SECURITY.md
NOTICE
docs/product/feature-scope.md
docs/security/diagnostics-redaction.md
docs/security/reverse-engineering-boundary.md
docs/protocol/wifi-ptp-ip.md
docs/protocol/bluetooth-le.md
docs/protocol/media-transfer.md
docs/protocol/error-model.md
docs/protocol/compatibility-matrix.md
docs/adr/0001-android-rust-boundary.md
docs/adr/0004-media-import-pipeline.md
docs/adr/0006-no-firmware-update-v1.md
```
