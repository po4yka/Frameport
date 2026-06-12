# ADR 0004: Media Import Pipeline

## Status

Accepted

## Date

2026-06-11

## Context

Frameport needs to import media from Fujifilm cameras into Android shared media storage. Expected import sources include Wi-Fi PTP-IP object transfer, USB PTP object transfer later, camera media object enumeration, thumbnails, full-size objects, JPEG, HEIF where supported, RAF where supported, and movie/video objects where supported later.

Storage must be Android-owned because Android shared media storage is governed by MediaStore. Android scoped storage and user media permissions are platform-specific, Android must own pending-item creation, cancellation, and finalization, Android must decide the destination collection and metadata, and Rust should not write arbitrary shared storage paths.

Transfer must be Rust-owned because camera object transfer is protocol-specific. PTP/PTP-IP transaction state belongs in Rust, retry/cancel/progress semantics are tied to protocol behavior, Rust can stream directly into an Android-provided output file descriptor, and Rust can keep protocol parsing and transfer diagnostics consistent.

## Decision

Frameport will use an Android-owned MediaStore pipeline with Rust-owned camera object streaming.

Production flow:

```text
Android/Kotlin:
  enumerate selected objects through repository/use case
  create MediaStore item with IS_PENDING = 1
  open ParcelFileDescriptor for output
  duplicate/transfer owned output fd to Rust
  observe transfer progress
  finalize MediaStore item with IS_PENDING = 0 on success
  delete/cancel pending item on failure or cancellation
  record import metadata in Room

Rust:
  receive object id/handle and owned output fd
  request object data from camera
  stream bytes into fd
  report progress
  support cancellation
  return typed transfer/protocol/storage errors
  close owned fd according to documented ownership
```

Rust must not write directly to arbitrary shared storage paths. Android must not implement camera object transfer protocol in MediaStore code. The import pipeline must be cancellable. Partial imports must not appear as complete media items. Import metadata must be stored locally only.

### Boundary Diagram

```text
Gallery / Import UI
  ↓
ImportViewModel
  ↓
ImportUseCase
  ↓
CameraRepository
  ├── media object metadata from Rust
  └── transfer request to Rust
        ↓
MediaStoreWriter
  ├── create pending item
  ├── open output fd
  └── pass owned fd
        ↓
Rust transfer engine
  ├── PTP/PTP-IP object read
  ├── progress events
  ├── cancellation
  └── write bytes to fd
        ↓
MediaStore finalization
  ↓
Room import catalog update
```

Simplified lifecycle:

```text
Discovered
  ↓
Selected
  ↓
PendingMediaStoreItemCreated
  ↓
TransferRunning
  ├── Success → MediaStoreFinalized → ImportRecorded
  ├── Cancelled → PendingItemDeleted → ImportCancelled
  └── Failed → PendingItemDeletedOrMarkedFailed → ImportFailed
```

## Consequences

Positive consequences:

* Android scoped storage behavior remains idiomatic
* Imported media is visible to the system media library
* Rust avoids Android storage APIs
* Rust can stream large files without holding them fully in memory
* Partial imports can be hidden using pending MediaStore items
* Transfer progress and cancellation can be modeled explicitly
* Room can store local import history without controlling shared storage
* The same import pipeline can support Wi-Fi and USB transports later

Negative consequences:

* Fd ownership must be precise
* Cancellation must coordinate Kotlin, Rust, MediaStore, and Room
* Duplicate detection requires careful metadata strategy
* MediaStore behavior can differ across Android versions and OEMs
* Large RAF/video files require robust progress and timeout handling
* Rust errors and Android storage errors must be merged into a clear domain model

## Alternatives Considered

### Alternative 1: Rust writes directly to shared storage paths

This alternative would pass Rust a filesystem path and have Rust write imported files directly.

Rejected because scoped storage makes direct shared-path writes fragile, MediaStore ownership belongs to Android, permissions become harder to reason about, partial files may become visible to the user, Android version behavior may differ, and privacy and deletion semantics become worse.

### Alternative 2: Kotlin downloads camera objects byte-by-byte

This alternative would have Kotlin own the protocol transfer and write to MediaStore.

Rejected because camera protocol transfer belongs in Rust, binary protocol handling would leak into Android storage code, retry/progress/cancel semantics would be fragmented, Rust diagnostics would not cover transfer behavior, and performance and allocation control would be worse.

### Alternative 3: Import into app-private storage first, then copy to MediaStore

This alternative would download the full object into app-private cache, then copy it into MediaStore.

Rejected as the default because it doubles disk I/O, requires enough free space for duplicate copies, complicates cancellation cleanup, increases time for large RAF/video files, and creates extra privacy-sensitive temporary files.

This may be accepted only for specific future cases where post-processing requires a complete temporary file.

### Alternative 4: Use Android Photo Picker or user-selected folders

This alternative would ask the user to select a destination through the system picker or document tree.

Rejected for v1 because camera import should be a predictable media-library workflow, MediaStore is the correct default for shared photos/videos, SAF/document-tree UX adds friction, and file visibility and metadata behavior are less consistent.

This may be reconsidered later for an advanced export feature.

## Import Pipeline

### Step 1: Media Object Discovery

Rust enumerates camera objects and returns metadata to Android.

Expected metadata:

```text
object id / handle
filename if available
object size if available
media format
capture timestamp if available
thumbnail availability
orientation if available
camera model if available
storage/card slot if available later
```

Android maps this metadata into domain models and optional Room cache entries.

### Step 2: User Selection

Android UI lets the user select objects.

Selection must be based on stable object identifiers and session identity, not UI list positions.

### Step 3: Destination Planning

Android determines:

```text
target collection:
  Images
  Video
  Downloads or app-specific fallback only if media type is unknown

display name
mime type
relative path
pending state
duplicate policy
```

Initial relative path recommendation:

```text
Pictures/Frameport/<camera-model-or-generic>/<yyyy-MM-dd>
```

Do not include full camera serial numbers in paths.

### Step 4: Pending MediaStore Item

Android creates a pending item:

```text
IS_PENDING = 1
DISPLAY_NAME = planned display name
MIME_TYPE = planned MIME type
RELATIVE_PATH = planned relative path
DATE_TAKEN if known and safe
```

Android opens a `ParcelFileDescriptor` for writing.

### Step 5: FD Transfer to Rust

Android duplicates or transfers an owned output file descriptor to Rust.

Ownership must be documented:

```text
If Rust owns fd:
  Rust closes fd after transfer/cancel/failure.

If Android keeps original descriptor object:
  Android must pass a duplicated fd to Rust.

Android must not close Rust-owned fd while Rust is still writing.
Rust must not close Android-owned fd.
```

### Step 6: Streaming Transfer

Rust streams the camera object into the fd.

Requirements:

```text
bounded buffers
progress events
cancellation checks
timeout handling
typed errors
no full-file memory buffering
```

### Step 7: Success Finalization

On success:

```text
Rust returns success
Android closes any Android-owned descriptor
Android sets IS_PENDING = 0
Android records import success in Room
Android emits user-visible completion state
```

### Step 8: Failure or Cancellation

On failure/cancellation:

```text
Rust stops transfer
Rust closes owned fd
Android closes Android-owned descriptor
Android deletes pending MediaStore item when possible
Android records failure/cancelled state only if useful for diagnostics
Android emits typed error state
```

Partial files must not be exposed as successfully imported media.

## Data Model

These conceptual domain models are illustrative, not required exact code.

```kotlin
data class CameraMediaObject(
    val id: CameraObjectId,
    val sessionId: CameraSessionId,
    val filename: String?,
    val sizeBytes: Long?,
    val format: CameraMediaFormat,
    val capturedAt: Instant?,
    val thumbnailState: ThumbnailState,
    val importState: ImportState,
)
```

```kotlin
sealed interface CameraMediaFormat {
    data object Jpeg : CameraMediaFormat
    data object Heif : CameraMediaFormat
    data object Raf : CameraMediaFormat
    data object Mov : CameraMediaFormat
    data object Mp4 : CameraMediaFormat
    data class Unknown(val rawCode: Long?) : CameraMediaFormat
}
```

```kotlin
sealed interface ImportState {
    data object NotImported : ImportState
    data class Pending(val jobId: ImportJobId) : ImportState
    data class Running(val jobId: ImportJobId, val progress: TransferProgress) : ImportState
    data class Imported(val mediaStoreUri: Uri) : ImportState
    data class Failed(val error: FujiError) : ImportState
    data object Cancelled : ImportState
}
```

Conceptual Room entities:

```text
ImportedMediaEntity:
  local id
  camera object id
  camera model
  firmware version if known
  filename hash or redacted name policy
  size bytes
  format
  captured timestamp
  imported timestamp
  MediaStore URI
  duplicate key
  import status

TransferHistoryEntity:
  job id
  started at
  ended at
  transport
  object count
  total bytes
  success count
  failure count
  typed error code if failed
```

Filenames and metadata privacy rules must follow `SECURITY.md`.

## Implementation Rules

### Android Rules

* Android owns MediaStore.
* Android owns pending-item lifecycle.
* Android owns Room import catalog updates.
* Android owns user-visible import notifications.
* Android must create pending items before transfer starts.
* Android must finalize items only after Rust reports success.
* Android must delete or clean failed pending items.
* Android must handle cancellation.
* Android must expose import state through Flow/StateFlow.
* Android must not perform PTP packet transfer logic.
* Android must not pass arbitrary filesystem paths to Rust for shared media writes.

### Rust Rules

* Rust owns camera object transfer protocol.
* Rust must stream to fd, not return full object bytes for large files.
* Rust must use bounded buffers.
* Rust must support cancellation.
* Rust must report progress.
* Rust must validate camera-reported object sizes.
* Rust must handle short reads and connection resets.
* Rust must return typed errors.
* Rust must not panic across JNI.
* Rust must not log private filenames, paths, or media contents.
* Rust must close owned fds according to documented ownership.

### JNI Rules

* JNI must expose coarse-grained transfer APIs.
* Do not call JNI for every tiny read chunk from Kotlin.
* Do not expose packet-level transfer APIs.
* Every output fd must have explicit ownership.
* Transfer callbacks must be low-frequency enough to avoid UI or JNI overhead.
* Progress callbacks should be throttled or batched if needed.
* Cancellation must be safe from Kotlin while Rust transfer is active.

Good API examples:

```text
listMedia(sessionId)
getThumbnail(sessionId, objectId)
downloadObjectToFd(sessionId, objectId, outputFd, transferOptions)
cancelTransfer(transferId)
```

Bad API examples:

```text
readNextObjectChunk(objectId)
writeChunkToCamera(...)
getRawPacket(...)
saveObjectToPath(path)
```

### Duplicate Detection Rules

Initial duplicate strategy:

```text
object id within session
filename
size bytes
capture timestamp
camera model
format
```

Do not rely only on filename.

If hashes are added later, compute them streaming and avoid double-reading large files unless justified.

### MIME and Extension Rules

Initial mapping:

```text
JPEG:
  extension .jpg or .jpeg
  MIME image/jpeg

HEIF:
  extension .heif or .heic depending source metadata
  MIME image/heif or image/heic

RAF:
  extension .raf
  MIME image/x-fuji-raf or application/octet-stream fallback

MOV:
  extension .mov
  MIME video/quicktime

MP4:
  extension .mp4
  MIME video/mp4

Unknown:
  preserve extension if safe
  MIME application/octet-stream fallback
```

Do not invent unsupported format support. Mark unknown formats explicitly.

## Failure Modes

Camera/protocol failures:

```text
Protocol.CameraBusy
Protocol.SessionClosed
Protocol.UnsupportedFunctionMode
Protocol.UnexpectedPacket
Transfer.ObjectNotFound
Transfer.ObjectInfoUnavailable
Transfer.PartialReadFailed
Transfer.CameraDisconnected
Transfer.Timeout
Transfer.Cancelled
```

Storage failures:

```text
Storage.MediaStoreCreateFailed
Storage.OutputFdOpenFailed
Storage.OutputFdInvalid
Storage.OutputWriteFailed
Storage.PendingItemFinalizeFailed
Storage.PendingItemDeleteFailed
Storage.InsufficientSpace
```

Data/format failures:

```text
Media.UnsupportedFormat
Media.UnknownMimeType
Media.InvalidFilename
Media.SizeMismatch
Media.DuplicateDetected
```

JNI/native failures:

```text
Native.FdOwnershipViolation
Native.PanicPrevented
Native.CallbackFailure
Native.SessionInvalid
```

User-facing diagnostics must distinguish:

```text
camera connection failure
camera protocol failure
storage failure
user cancellation
duplicate/skipped item
unsupported format
```

## Testing Implications

Android unit tests should cover:

* Import state reducer tests
* Duplicate detection tests
* Destination planning tests
* MIME mapping tests
* Filename sanitization tests
* Cancellation state tests
* Failure mapping tests

Android instrumentation tests should cover:

* MediaStore pending item create/finalize
* Pending item cleanup on failure
* Output fd open/write/close behavior
* Large-file import simulation
* Notification/progress behavior if implemented

Rust tests should cover:

* Fake object transfer tests
* Partial read tests
* Transfer cancellation tests
* Object size mismatch tests
* Output fd write error tests where feasible
* Bounded-buffer tests
* Protocol timeout tests

Integration tests should cover:

* Import JPEG from Fujifilm X-T5
* Import RAF from Fujifilm X-T5 if supported
* Import HEIF if supported and enabled on camera
* Import large file
* Cancel import mid-transfer
* Disconnect camera mid-transfer
* Repeated imports and duplicate behavior
* Android device/OEM matrix

## Security and Privacy Implications

Real photos and videos are private user data. Filenames may contain personal information. EXIF may contain location and device metadata. Import history is sensitive.

Diagnostics must redact filenames by default unless the user opts in. Precise GPS must not be logged. Full camera serial numbers must not be stored in import paths. Camera Wi-Fi passphrases must never be logged during import.

Partial files must not be exposed as successful imports. App-private temporary files should be minimized. The local import catalog must be deletable by user action later.

Allowed by default:

```text
session id
transport
object count
format category
size bytes
progress percentage
elapsed time
typed error code
```

Redacted or forbidden by default:

```text
private filenames
full filesystem paths
precise GPS
camera Wi-Fi passphrase
pairing secret
full serial number
full MAC address
raw media bytes
raw EXIF dump
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
docs/android/mediastore-import.md
docs/rust/fd-ownership.md
docs/protocol/media-transfer.md
docs/protocol/error-model.md
docs/security/diagnostics-redaction.md
docs/protocol/compatibility-matrix.md
```
