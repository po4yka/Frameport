# Media Transfer Protocol

## Purpose

This document defines how Frameport discovers, describes, previews, and transfers media objects from a connected camera. Media transfer is one of the primary v1 workflows.

Primary v1 target:

```text
Wi-Fi PTP-IP session
camera media object enumeration
object metadata read
thumbnail fetch where available
single object import
batch object import
JPEG import
RAF import if verified
HEIF import if verified
MediaStore pipeline integration
progress
cancellation
diagnostics
```

This is an engineering/protocol document, not a compatibility guarantee.

## Scope

Covered:

```text
camera object handles / object IDs
object metadata
object count
thumbnail fetch
full object transfer
partial/chunked object reads
transfer progress
transfer cancellation
format mapping
duplicate detection inputs
transfer diagnostics
camera-side transfer errors
transport-independent transfer concepts
```

Not covered:

```text
Android MediaStore finalization details
Wi-Fi socket fd handoff details
Bluetooth LE handoff details
USB session details
live-view frame streaming
firmware update
cloud sync
account behavior
analytics
```

Related documents:

```text
docs/adr/0002-wifi-socket-fd-handoff.md
docs/adr/0004-media-import-pipeline.md
docs/protocol/wifi-ptp-ip.md
docs/protocol/bluetooth-le.md
docs/protocol/usb-ptp.md
docs/protocol/liveview.md
```

## Architecture Overview

Media transfer is a protocol-owned workflow with Android-owned storage.

Frameport uses this split:

```text
Android/Kotlin:
  owns user selection
  owns import queue UI
  owns MediaStore pending item creation
  owns output file descriptor creation
  owns Room import catalog
  owns user-visible progress/cancel UI

Rust:
  owns camera object enumeration
  owns object metadata parsing
  owns thumbnail fetch
  owns full object transfer
  owns chunk/partial read protocol
  owns progress calculation
  owns cancellation checks
  owns protocol/transfer diagnostics
  writes bytes into Android-provided output fd
```

```text
Connected camera session
  ↓
Rust media enumeration
  ↓
CameraMediaObject list
  ↓
Android gallery / selection UI
  ↓
Android creates MediaStore pending item
  ↓
Android passes owned output fd to Rust
  ↓
Rust streams camera object into fd
  ↓
Android finalizes MediaStore item
  ↓
Room import catalog records result
```

Rust must not write directly to arbitrary shared storage paths. Android must not implement camera object transfer protocol inside UI, MediaStore, or ViewModel code.

## Android/Rust Boundary

Android owns:

```text
object selection state
import queue state
MediaStore destination planning
filename sanitization for Android storage
MIME type assignment for MediaStore
pending item lifecycle
output fd creation
output fd duplication/ownership transfer
progress UI
cancellation UI
Room import catalog
user-visible errors
```

Rust owns:

```text
object handle enumeration
object info request/parse
thumbnail request/parse
object transfer request
partial object/chunk reads
camera-side transfer retries where appropriate
progress byte accounting
transfer cancellation checks
object size verification
format code interpretation where available
protocol errors
transfer diagnostics
```

Explicitly forbidden:

```text
Composable directly starting object transfer
ViewModel directly calling JNI transfer APIs
Kotlin feature module parsing raw PTP object packets
Rust creating MediaStore items
Rust writing shared storage paths
Rust logging private filenames or raw EXIF dumps
```

## Transfer Lifecycle

```text
SessionReady
  ↓
EnumeratingObjects
  ├── Success → MediaListReady
  └── Failure → Error

MediaListReady
  ↓
UserSelectsObjects
  ↓
PreparingImport
  ↓
PendingMediaStoreItemCreated
  ↓
TransferStarted
  ├── Progress → TransferRunning
  ├── Cancel → TransferCancelled
  ├── Failure → TransferFailed
  └── Success → TransferCompleted

TransferCompleted
  ↓
MediaStoreFinalized
  ↓
ImportRecorded
```

Cleanup requirements:

```text
On success:
  close Rust-owned output fd
  finalize MediaStore item
  record import metadata

On cancellation:
  stop Rust transfer
  close Rust-owned output fd
  delete pending MediaStore item where possible
  emit cancelled state

On failure:
  close Rust-owned output fd
  delete or mark pending MediaStore item failed
  record diagnostic state
  emit typed error
```

## Object Enumeration

Expected flow:

```text
1. Rust asks camera for object count or object handles.
2. Rust receives camera object identifiers.
3. Rust requests metadata for visible/importable objects.
4. Rust returns domain-level media objects to Android.
```

Expected object fields:

```text
object id / handle
filename if available
object size if available
format code if available
capture timestamp if available
orientation if available
storage/card slot if available later
thumbnail availability if known
raw format category if unknown
```

Rules:

```text
object identifiers are session-scoped unless verified otherwise
UI must not rely on list position as object identity
object list may become stale if camera state changes
object enumeration failure must not crash gallery UI
unsupported object formats must be represented explicitly
```

## Object Metadata

Allowed metadata by default:

```text
format category
size bytes
capture timestamp if safe
orientation
thumbnail availability
camera model
firmware version
storage/card slot category if known
```

Redacted or sensitive metadata:

```text
private filenames
EXIF GPS
owner/author/copyright EXIF fields
full filesystem-like camera paths
full camera serial number
full MAC address
```

Rules:

```text
metadata from camera is untrusted input
filenames must be sanitized before MediaStore use
format code must be preserved if unknown
unknown metadata must be represented as unknown, not guessed
```

## Thumbnail Transfer

Thumbnail transfer is used for gallery browsing and selection.

Expected behavior:

```text
request thumbnail for object
receive bounded thumbnail bytes
decode/render on Android side
cache thumbnail if product scope allows
handle missing thumbnail
handle unsupported format
handle timeout/failure
```

Rules:

```text
thumbnail fetch must not block UI thread
thumbnail bytes are user media and private
thumbnail diagnostics must not include raw bytes
thumbnail cache must follow privacy/storage policy
thumbnail failure must not block full object import
```

Error states:

```text
Thumbnail.NotAvailable
Thumbnail.UnsupportedFormat
Thumbnail.Timeout
Thumbnail.DecodeFailed
Thumbnail.CameraDisconnected
```

## Full Object Transfer

Expected behavior:

```text
Android creates output fd
Rust requests object data from camera
Rust reads object in bounded chunks
Rust writes chunks to fd
Rust reports progress
Rust checks cancellation
Rust verifies final size when possible
Rust returns success or typed failure
```

Rules:

```text
do not buffer whole object in memory
use bounded buffers
support large RAF files
support large video files later only if accepted into scope
support cancellation
support timeout handling
support connection reset handling
ensure output fd ownership is clear
do not log raw media bytes
```

Object transfer may be transport-independent at the domain level, but transport-specific underneath:

```text
Wi-Fi PTP-IP:
  v1 primary path

USB PTP:
  later

BLE:
  not used for full object transfer
```

## Media Format Handling

Initial format categories:

```text
JPEG:
  v1 candidate
  MIME image/jpeg
  extension .jpg or .jpeg

HEIF:
  v1 candidate only after verification
  MIME image/heif or image/heic based on available metadata
  extension .heif or .heic

RAF:
  v1 candidate only after verification
  MIME image/x-fuji-raf if supported by Android/media tooling, otherwise application/octet-stream fallback
  extension .raf

MOV:
  later unless explicitly accepted
  MIME video/quicktime
  extension .mov

MP4:
  later unless explicitly accepted
  MIME video/mp4
  extension .mp4

Unknown:
  not imported by default unless safe
  preserve raw format code
  show unsupported/unknown state
```

Rules:

```text
do not invent format support
do not claim RAF/HEIF support until verified on target camera/firmware
unknown formats must be safe to skip
format mapping must be tested
MIME mapping must be tested
file extensions must be sanitized
```

## Progress and Cancellation

Progress fields:

```text
transfer id
object id
bytes transferred
total bytes if known
percentage if total known
elapsed time
estimated state if useful
current phase
```

Phases:

```text
Preparing
OpeningOutput
RequestingObject
Transferring
Finalizing
Completed
Cancelling
Cancelled
Failed
```

Rules:

```text
progress must be throttled enough to avoid UI/JNI overhead
cancellation must be safe at any phase
cancellation must close resources
cancellation must not leave completed-looking partial files
user cancellation is not an error
camera disconnect during transfer is an error
```

## Duplicate Detection Inputs

Duplicate detection is defined here as inputs, not final perfect identity.

Initial duplicate inputs:

```text
camera model
firmware version if known
object id / handle for active session
filename if available and safe
size bytes
capture timestamp
format category
MediaStore URI after import
optional content hash later
```

Rules:

```text
do not rely only on filename
do not rely only on object id across sessions unless verified
do not expose private filenames by default in diagnostics
hashing large files must be justified
duplicate detection must never delete user media automatically in v1
```

## Error Model

Enumeration errors:

```text
Media.ObjectCountUnavailable
Media.ObjectHandlesUnavailable
Media.ObjectInfoUnavailable
Media.UnsupportedObjectFormat
Media.StaleObjectList
```

Thumbnail errors:

```text
Thumbnail.NotAvailable
Thumbnail.UnsupportedFormat
Thumbnail.Timeout
Thumbnail.DecodeFailed
Thumbnail.CameraDisconnected
```

Transfer errors:

```text
Transfer.ObjectNotFound
Transfer.ObjectInfoUnavailable
Transfer.UnsupportedFormat
Transfer.PartialReadFailed
Transfer.SizeMismatch
Transfer.CameraDisconnected
Transfer.Timeout
Transfer.Cancelled
Transfer.OutputWriteFailed
```

Storage/output errors:

```text
Storage.OutputFdInvalid
Storage.OutputWriteFailed
Storage.MediaStoreCreateFailed
Storage.MediaStoreFinalizeFailed
Storage.PendingItemDeleteFailed
Storage.InsufficientSpace
```

Protocol errors:

```text
Protocol.CameraBusy
Protocol.UnsupportedOperation
Protocol.SessionClosed
Protocol.UnexpectedPacket
Protocol.InvalidPacketLength
```

User-facing diagnostics must distinguish:

```text
camera object unavailable
unsupported format
camera disconnected
storage failure
user cancellation
protocol failure
duplicate/skipped item
```

## Diagnostics

Allowed by default:

```text
app version
Android version
camera model
firmware version
transport
operation name
object count
format category
approximate object size
bytes transferred
progress percentage
elapsed time
typed error code
retry count
session id
```

Redacted by default:

```text
private filenames
full paths
raw media bytes
thumbnail bytes
EXIF dumps
EXIF GPS
full serial number
full MAC address
Wi-Fi passphrase
pairing secret
raw packet payloads
```

Diagnostic timeline example:

```text
MediaEnumerationStarted
ObjectHandlesReceived(count=...)
ObjectInfoRead(format=JPEG, size=...)
ThumbnailFetchStarted
ThumbnailFetchSucceeded
TransferStarted(format=JPEG)
TransferProgress(percentage=...)
TransferCompleted
MediaStoreFinalized
```

Failure timeline example:

```text
TransferStarted(format=RAF)
TransferProgress(percentage=42)
CameraDisconnected
Error(Transfer.CameraDisconnected)
PendingItemDeleted
CleanupCompleted
```

## Compatibility and Validation

Transfer support must be claimed per:

```text
camera model
firmware version
Android device model
Android OS version
Frameport version/commit
transport
media format
feature
```

A "supported" claim requires:

```text
real hardware test
feature-specific pass result
successful import into MediaStore
redacted diagnostic evidence
compatibility matrix update
repeatable behavior
```

Do not claim:

```text
object enumeration support implies import support
JPEG support implies RAF support
RAF support implies HEIF support
Wi-Fi transfer support implies USB transfer support
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
whether object handles are stable across sessions
whether object enumeration differs by storage/card slot
whether RAF transfer is available through the same path on every camera
whether HEIF transfer depends on camera settings
whether video object transfer requires separate handling
whether thumbnails are available for all formats
whether thumbnail format differs by camera
whether object size is always reliable
whether camera busy behavior differs during transfer
whether partial/resume transfer is supported or practical
whether duplicate detection can rely on object metadata across sessions
```

Unknowns must be resolved with project-owned tests, sanitized traces, and compatibility matrix updates.

## Security and Privacy Rules

```text
Do not log private filenames by default.
Do not log full paths by default.
Do not log raw media bytes.
Do not log thumbnail bytes.
Do not log raw EXIF dumps.
Do not log EXIF GPS.
Do not log camera Wi-Fi passphrases.
Do not include real user photos in tests.
Do not include real user media in diagnostic bundles.
Do not upload media or diagnostics to cloud in v1.
Do not automatically delete user media when detecting duplicates.
```

All media-transfer diagnostics must follow:

```text
docs/security/diagnostics-redaction.md
docs/security/reverse-engineering-boundary.md
```

## Testing Requirements

Rust unit tests:

```text
object info parser tests
format code mapping tests
thumbnail response parser tests
transfer state tests
progress calculation tests
cancellation tests
size mismatch tests
invalid packet length tests
```

Rust fake camera tests:

```text
object enumeration success
object enumeration failure
thumbnail success/failure
JPEG transfer success
RAF transfer success if fixture exists
HEIF transfer success if fixture exists
transfer timeout
connection reset mid-transfer
cancel mid-transfer
size mismatch
unsupported format
```

Android unit tests:

```text
import queue state tests
format-to-MIME mapping tests
destination planning tests
duplicate detection input tests
filename sanitization tests
ViewModel progress tests
failure mapping tests
```

Android instrumentation tests:

```text
MediaStore pending item creation
output fd write simulation
pending item finalization
pending item cleanup on failure
large file simulation
cancel during import
```

Manual hardware tests:

```text
Fujifilm X-T5 media enumeration
JPEG thumbnail fetch
JPEG import
RAF import if supported
HEIF import if supported
batch import
cancel import
disconnect during import
repeat import and duplicate behavior
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
docs/protocol/wifi-ptp-ip.md
docs/protocol/bluetooth-le.md
docs/protocol/error-model.md
docs/product/feature-scope.md
```
