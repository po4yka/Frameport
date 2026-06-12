---
name: mediastore-import
description: Use when authoring or modifying the MediaStore import pipeline in camera/media (MediaStoreWriter) or feature/import (ImportViewModel, ImportUseCase) — covering ContentResolver.insert with IS_PENDING=1, RELATIVE_PATH, collection routing per format (JPEG/HEIF/RAF/MOV/MP4), ParcelFileDescriptor/fd ownership transfer to Rust, IS_PENDING finalization, stale-pending cleanup, MIME mapping, and scoped-storage permission handling for API 29+. Also triggers on changes to CameraMediaFormat, ImportState, duplicate detection, or any MediaStore-related failure mode in the import pipeline.
---

# MediaStore Import Pipeline -- Frameport

## Purpose

This skill codifies the scoped-storage MediaStore import pattern for `camera/media` and `feature/import`. It covers the full Android-side lifecycle: collection routing, pending-item creation, fd ownership transfer to the Rust transfer engine (`fuji-ffi`/`fuji-transfer`), success finalization, failure cleanup, stale-pending recovery, and permission handling across the minSdk 31 baseline through API 34.

## When to consult

- Authoring or modifying `MediaStoreWriter` in `:camera:media`.
- Authoring or modifying `ImportUseCase`, `ImportRepository`, or `ImportViewModel` in `:feature:import`.
- Adding or changing format support in `CameraMediaFormat` (`:core:model`).
- Adding any `ContentResolver.insert`, `openFileDescriptor`, or `update`/`delete` call touching shared storage.
- Reviewing a diff that handles `IS_PENDING`, `RELATIVE_PATH`, or `ParcelFileDescriptor` in this workspace.
- Handling MediaStore errors or stale-pending cleanup on session start.

## Layer invariants (do not violate)

- `MediaStoreWriter` in `:camera:media` is the sole class that touches `ContentResolver`. Nothing above it (`ImportUseCase`, `ImportViewModel`, Composables) calls `ContentResolver` directly.
- `ImportViewModel` does not call JNI. It calls `ImportUseCase` → `CameraRepository` interface → (`MediaStoreWriter` for Android side, JNI bridge for Rust side) as separate concerns.
- Composables in `:feature:import` and `:feature:gallery` do not read or write files; they consume `ImportState` from `StateFlow`.
- Rust (`fuji-transfer`, `fuji-ffi`) receives a raw `Int` fd and streams camera object bytes into it. Rust never touches `ContentResolver` or any MediaStore URI.

---

## 1. Collection routing by format

Choose the target `Uri` collection and `MIME_TYPE` based on `CameraMediaFormat` before calling `ContentResolver.insert`. MediaStore will reject unknown MIME types from the `Images` collection on many OEMs — route RAW to `Downloads` to avoid silent reclassification.

| `CameraMediaFormat` | Collection | MIME type | RELATIVE_PATH prefix |
|---|---|---|---|
| `Jpeg` | `MediaStore.Images.Media` | `image/jpeg` | `Pictures/Frameport/<model>/<date>/` |
| `Heif` | `MediaStore.Images.Media` | `image/heic` (preferred) or `image/heif` | `Pictures/Frameport/<model>/<date>/` |
| `Raf` | `MediaStore.Downloads` | `image/x-fuji-raf` (attempt), fall back to `application/octet-stream` | `Downloads/Frameport/<model>/<date>/` |
| `Mov` | `MediaStore.Video.Media` | `video/quicktime` | `Movies/Frameport/<model>/<date>/` |
| `Mp4` | `MediaStore.Video.Media` | `video/mp4` | `Movies/Frameport/<model>/<date>/` |
| `Unknown` | `MediaStore.Downloads` | `application/octet-stream` | `Downloads/Frameport/<model>/<date>/` |

**Note on `image/heic` vs `image/heif`:** these are distinct MIME types. Prefer `image/heic` for Fujifilm `.heic` files (H.265-based HEIF). Verify the actual extension in `CameraMediaObject.filename` before choosing — the camera may report either. When in doubt, fall back to `image/heif`.

**Note on RAF MIME acceptance:** whether `image/x-fuji-raf` is accepted into `MediaStore.Images` varies by device and Android version. The safe default is `MediaStore.Downloads`; verify against a real device with your specific Android version range before changing this routing.

Get the canonical collection URI using the volume-scoped API (API 29+, always available at minSdk 31):

```kotlin
// Always use VOLUME_EXTERNAL_PRIMARY; do NOT use the legacy EXTERNAL_CONTENT_URI constant.
val imagesUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
val videoUri  = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
val downloadsUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
```

---

## 2. Creating a pending MediaStore item

Always set `IS_PENDING = 1` before the transfer starts. The item is invisible to all other apps and system queries until finalized.

```kotlin
// In MediaStoreWriter, always called from Dispatchers.IO
internal suspend fun createPendingItem(
    resolver: ContentResolver,
    obj: CameraMediaObject,
    destination: ImportDestination, // computed by DestinationPlanner
): Uri {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, destination.displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, destination.mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, destination.relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
        // DATE_TAKEN: only set when captured timestamp is known and does not expose GPS.
        obj.capturedAt?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it.toEpochMilli()) }
        // SIZE: set when camera object info reports a trusted size; aids duplicate detection.
        obj.sizeBytes?.let { put(MediaStore.MediaColumns.SIZE, it) }
    }
    return resolver.insert(destination.collectionUri, values)
        ?: throw IOException("MediaStore insert returned null") // maps to Storage.MediaStoreCreateFailed in ImportState.Failed
}
```

**`RELATIVE_PATH` must end with a trailing slash.** Omitting it causes the last path component to be treated as a filename prefix on some OEMs, misrouting files.

```kotlin
// Correct
"Pictures/Frameport/X-T5/2026-06-12/"

// Wrong — trailing slash missing
"Pictures/Frameport/X-T5/2026-06-12"
```

Do not include full camera serial numbers in `RELATIVE_PATH` (ADR 0004 privacy rule).

---

## 3. Opening the output file descriptor

```kotlin
// "rwt" mode truncates on open; safe for a newly-created pending item.
val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(pendingUri, "rwt")
    ?: throw IOException("openFileDescriptor returned null for $pendingUri") // maps to Storage.OutputFdOpenFailed in ImportState.Failed
```

Never derive a filesystem path from `MediaStore.MediaColumns.DATA` and open it directly. Always use `ContentResolver.openFileDescriptor` on the URI returned by `insert`.

---

## 4. Fd ownership transfer to Rust

The canonical Frameport pattern uses `Os.dup` to avoid the `detachFd` double-close hazard.

```kotlin
import android.system.Os

// Duplicate the descriptor. Rust receives the dup'd fd and owns its close.
// The original PFD stays alive (held in this scope) until Rust reports completion.
val dupFd: Int = Os.dup(pfd.fileDescriptor)

try {
    // JNI call — passes dupFd as jint. Rust closes it when done.
    // downloadObjectToFd is a planned API on the NativeFujiSdk JNI interface (see ADR 0004 §good-api-examples).
    // Exact call site will depend on the concrete NativeFujiSdk implementation; verify against the implemented API.
    val transferId = nativeFujiSdk.downloadObjectToFd(
        sessionId = session.sessionId.value,
        objectId = obj.id.value,
        outputFd = dupFd,
        options = transferOptions,
    )
    awaitTransferCompletion(transferId) // suspends; Rust writes to dupFd
} finally {
    // PFD close always happens here, regardless of success/cancel/failure.
    pfd.close()
}
```

**Fd ownership rules (hard):**

- If Rust receives a dup'd fd via `Os.dup`: Rust closes it; Kotlin closes the original `ParcelFileDescriptor` in the `finally` block. Kotlin must never call `pfd.close()` before Rust reports completion of the write.
- If `detachFd()` is used instead: `pfd` is invalidated. Do NOT call `pfd.close()` after `detachFd()`. This approach is more error-prone; prefer `Os.dup`.
- `ParcelFileDescriptor.getFd()` returns the raw fd without transferring ownership. If this int is passed to Rust, the PFD must be kept alive (held in a variable) until Rust finishes. Let the PFD go out of scope or be closed by a GC finalizer while Rust is writing causes a use-after-close race.

---

## 5. Finalization (success path)

```kotlin
// Always run in Dispatchers.IO. Run in NonCancellable context so a coroutine
// cancellation cannot skip finalization and leave IS_PENDING=1 indefinitely.
withContext(NonCancellable + Dispatchers.IO) {
    val finalValues = ContentValues().apply {
        put(MediaStore.MediaColumns.IS_PENDING, 0)
    }
    val rows = resolver.update(pendingUri, finalValues, null, null)
    if (rows == 0) {
        // Log typed error; do not throw — item may still be usable.
        logger.warn("IS_PENDING finalization updated 0 rows for $pendingUri")
    }
}
```

Failing to clear `IS_PENDING` leaves the file permanently invisible to all apps (Gallery, Google Photos, system media scanner). The `NonCancellable` context is required — if the import coroutine is cancelled between transfer completion and finalization, the item would otherwise remain hidden.

---

## 6. Cleanup (failure and cancellation paths)

```kotlin
// In the failure/cancel branch. Also run under NonCancellable.
withContext(NonCancellable + Dispatchers.IO) {
    try {
        resolver.delete(pendingUri, null, null)
    } catch (e: Exception) {
        logger.error("Failed to delete pending item $pendingUri: ${e.message}")
        // Surface as Storage.PendingItemDeleteFailed in ImportState if needed.
    }
}
```

A stale `IS_PENDING=1` item whose app is still installed is not auto-cleaned by Android until the app is uninstalled (API 31+ behaviour). Always delete explicitly on failure and cancellation.

---

## 7. Stale-pending cleanup on session start

If the app was killed while `IS_PENDING=1` items existed, those rows persist as invisible orphans. Clean them at `ImportUseCase` initialization or session start.

```kotlin
// Query own-app pending items. OWNER_PACKAGE_NAME is auto-set by MediaStore to the
// inserting package; no permission is needed to query own items.
private suspend fun cleanStalePendingItems(resolver: ContentResolver) =
    withContext(Dispatchers.IO) {
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )
        val selection = "${MediaStore.MediaColumns.IS_PENDING} = 1 AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        val selectionArgs = arrayOf("dev.po4yka.frameport")

        for (uri in collections) {
            try {
                resolver.delete(uri, selection, selectionArgs)
            } catch (e: Exception) {
                logger.warn("Stale-pending cleanup failed for $uri: ${e.message}")
            }
        }
    }
```

This is safe at minSdk 31 — `IS_PENDING`, `OWNER_PACKAGE_NAME`, and `MediaStore.Downloads` are all available without any API-level guard.

---

## 8. Permissions

No special permission is required for the import pipeline itself. Frameport only inserts media it transfers (own-app writes on API 29+ require no permission).

Declare the following in the manifest for read access (needed if Frameport ever reads media created by other apps, e.g., deduplication scan):

```xml
<!-- API 31–32: legacy read permission for other-app media -->
<uses-permission
    android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />

<!-- API 33+: granular media read permissions -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />

<!-- API 34+: partial photo library access; declare alongside the above -->
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
```

Check at runtime before requesting on API 33+:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    // Request READ_MEDIA_IMAGES / READ_MEDIA_VIDEO
} else {
    // Request READ_EXTERNAL_STORAGE
}
```

Do NOT declare or request `ACCESS_MEDIA_LOCATION`. Frameport is privacy-first — location data must not be read from EXIF via MediaStore.

Do NOT declare `WRITE_EXTERNAL_STORAGE` without `maxSdkVersion="28"`. It is never needed on API 29+ for own-app insertions.

---

## 9. Coroutine dispatcher discipline

Every `ContentResolver` call (`insert`, `openFileDescriptor`, `update`, `delete`) must run on `Dispatchers.IO`. Calling from the main thread will ANR on large MediaStore databases.

```kotlin
// In MediaStoreWriter — all public functions are suspend and internally switch dispatcher:
suspend fun createPendingItem(...): Uri = withContext(Dispatchers.IO) { ... }
suspend fun openOutputFd(...): ParcelFileDescriptor = withContext(Dispatchers.IO) { ... }
suspend fun finalize(...) = withContext(NonCancellable + Dispatchers.IO) { ... }
suspend fun deletePending(...) = withContext(NonCancellable + Dispatchers.IO) { ... }
```

---

## 10. Duplicate detection inputs

The initial duplicate key (per ADR 0004) uses these fields from `CameraMediaObject`:

```
objectId (within session)
filename (if reported by camera)
sizeBytes (if reported by camera)
capturedAt (if reported by camera)
cameraModel (if known from session)
format (CameraMediaFormat)
```

Do not rely on filename alone — cameras may reuse names across card formats or firmware versions. If `SIZE` is known, set it in the `ContentValues` at insert time to assist downstream deduplication queries.

---

## 11. Privacy rules for import metadata

From ADR 0004 — enforced in `MediaStoreWriter` and diagnostics:

Allowed in logs and diagnostics:
- Session id, transport, object count, format category, size bytes, progress %, elapsed time, typed error code.

Never logged or stored in `RELATIVE_PATH`:
- Private filenames, full filesystem paths, precise GPS, camera Wi-Fi passphrase, pairing secrets, full serial numbers, full MAC addresses, raw media bytes, raw EXIF dumps.

Redact filenames in diagnostics by default unless the user explicitly opts in.

---

## Key pitfalls

**P1 — `IS_PENDING` not cleared after success.** The file is permanently invisible to all other apps. Finalization in `NonCancellable` context is required so coroutine cancellation cannot skip it.

**P2 — `RELATIVE_PATH` missing trailing slash.** The system treats the last component as a filename prefix on some OEMs, misrouting files. Always end with `/`.

**P3 — Double-close after `detachFd()`.** Calling `pfd.close()` after `detachFd()` causes `EBADF` in native code. Prefer `Os.dup` to give Rust its own fd and keep the original PFD lifecycle in Kotlin.

**P4 — PFD goes out of scope while Rust is still writing.** If `getFd()` is used (not `detachFd()`), the PFD must be kept alive until Rust signals completion. A GC finalizer or try-with-resources closing the PFD early causes a use-after-close race on the raw fd.

**P5 — RAF routed to `MediaStore.Images`.** Many OEMs reject or silently reclassify `image/x-fuji-raf` in the Images collection. Always route RAF to `MediaStore.Downloads`.

**P6 — Legacy URI `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`.** This bypasses the volume-scoped API and behaves inconsistently on multi-volume devices. Always use `getContentUri(VOLUME_EXTERNAL_PRIMARY)`.

**P7 — Using `DATA` column to get a path.** Bypass of MediaStore permissions; breaks on scoped storage. Never read `DATA` for write destinations; always open via `ContentResolver.openFileDescriptor` on the URI returned by `insert`.

**P8 — Stale pending items on app crash.** A killed process leaves `IS_PENDING=1` rows as invisible orphans. Query and delete own-app pending items at session start (see §7).

**P9 — `ContentResolver` calls on main thread.** Will ANR. All MediaStore calls must be on `Dispatchers.IO`.

**P10 — `image/heic` vs `image/heif` confusion.** These are distinct MIME types. Prefer `image/heic` for H.265-based HEIF files from Fujifilm cameras; verify against actual device output before assuming either.

---

## Failure taxonomy (from ADR 0004)

Map internal errors to these typed values in `ImportState.Failed` (typed as `FujiError` per ADR 0004; exact sealed class hierarchy to be confirmed when implemented):

```
Storage.MediaStoreCreateFailed    — insert() returned null
Storage.OutputFdOpenFailed        — openFileDescriptor() returned null
Storage.OutputFdInvalid           — dup'd fd rejected by OS
Storage.OutputWriteFailed         — Rust reports write error on fd
Storage.PendingItemFinalizeFailed — update() for IS_PENDING=0 affected 0 rows
Storage.PendingItemDeleteFailed   — delete() failed in failure/cancel path
Storage.InsufficientSpace         — IOException with ENOSPC from write
Media.UnknownMimeType             — format not in routing table
Media.SizeMismatch                — camera-reported size vs bytes written mismatch
```

Note: ADR 0004 also defines `Media.UnsupportedFormat`, `Media.InvalidFilename`, `Media.DuplicateDetected`, and `Native.*` error variants not listed above. Add them as the pipeline is implemented.

---

## Related ADRs and docs

- `docs/adr/0004-media-import-pipeline.md` — authoritative pipeline spec; this skill operationalizes it.
- `docs/adr/0001-android-rust-boundary.md` — layer boundary rules for Kotlin/Rust split.
- `docs/adr/0002-wifi-socket-fd-handoff.md` — fd ownership transfer patterns (general).
- `docs/rust/fd-ownership.md` — fd ownership contract between Kotlin and Rust (verify this file exists; referenced in ADR 0004).
- `docs/security/diagnostics-redaction.md` — redaction rules for filenames and EXIF.

## Related skills

- `rust-android-jni` — JNI fd ownership, `Os.dup` / `detachFd` usage, `downloadObjectToFd` API shape, panic containment.
- `rust-android-build` — `.so` packaging and NDK constraints for `fuji-ffi`.
- `rust-discipline` — Rust-side fd write discipline, bounded buffers, cancellation.
- `compose` — `ImportState` consumption in Composables; what Composables must not do.
- `diagnostics-system` — typed error propagation from the import pipeline into diagnostics.

---

## References

1. Android Developers — Access media files from shared storage: https://developer.android.com/training/data-storage/shared/media
2. MediaStore.MediaColumns API reference: https://developer.android.com/reference/android/provider/MediaStore.MediaColumns
3. MediaStore.Images.Media API reference: https://developer.android.com/reference/android/provider/MediaStore.Images.Media
4. MediaStore.Downloads API reference: https://developer.android.com/reference/android/provider/MediaStore.Downloads
5. ParcelFileDescriptor API reference: https://developer.android.com/reference/android/os/ParcelFileDescriptor
6. Android 13 behavior changes — Granular media permissions: https://developer.android.com/about/versions/13/behavior-changes-13#granular-media-permissions
7. Storage updates in Android 11: https://developer.android.com/about/versions/11/privacy/storage
8. Frameport ADR 0004: Media Import Pipeline — `docs/adr/0004-media-import-pipeline.md`
9. libopenraw — RAF format reference: https://libopenraw.freedesktop.org/formats/raf/
