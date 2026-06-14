package dev.po4yka.frameport.camera.media

import dev.po4yka.frameport.camera.api.CameraMediaFormat
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.ImportState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.storage.catalog.ImportCatalog
import kotlinx.coroutines.flow.Flow

/**
 * Orchestrates the full MediaStore import lifecycle for a single camera object.
 *
 * Implements the ADR-0004 steps 4–8:
 *   4. Create a pending MediaStore row (IS_PENDING=1).
 *   5. Open a write ParcelFileDescriptor for that row.
 *   6. Pass the raw fd integer to [FujiNativeSdk.downloadObjectToFd] (borrow semantics — see G1).
 *   7. Collect the Flow<TransferProgress>, emitting ImportState.Running per progress event.
 *   8a. On success: close pfd → finalizePending → record in ImportCatalog → emit Imported.
 *   8b. On failure: close pfd → deletePending → emit Failed(redacted FrameportError.Unknown).
 *   8c. On cancellation: close pfd → deletePending → emit Cancelled → rethrow CancellationException.
 */
interface MediaStoreWriter {
    /**
     * Runs the full import lifecycle and emits [ImportState] transitions.
     *
     * Emits:
     *   - [ImportState.Running] for each [TransferProgress] from the native SDK.
     *   - [ImportState.Imported] (with a MediaStore content:// URI string) on success.
     *   - [ImportState.Failed] (with a redacted [FrameportError.Unknown]) on any error.
     *   - [ImportState.Cancelled] if the collecting coroutine is cancelled; then rethrows.
     *
     * fd ownership (G1 / docs/rust/fd-ownership.md):
     *   The [ParcelFileDescriptor] opened for the pending row is ANDROID-OWNED throughout.
     *   Its raw int fd (pfd.fd) is passed to Rust as a BORROWED fd: Rust dups it internally
     *   and closes only its own dup. Android retains and closes the original pfd in all paths
     *   (success, failure, cancellation). [ParcelFileDescriptor.detachFd] MUST NOT be called.
     *
     * NOT cancel-safe: writes to an fd; partial writes are not rolled back on cancellation.
     * The pending MediaStore row is deleted on cancellation (cleanup is best-effort).
     */
    // NOT cancel-safe: writes to outputFd; partial writes not rolled back. Pending row is
    // deleted on cancellation but the partial bytes are not recoverable.
    fun importToMediaStore(
        sessionId: SessionId,
        handle: CameraObjectHandle,
        format: CameraMediaFormat,
    ): Flow<ImportState>
}
