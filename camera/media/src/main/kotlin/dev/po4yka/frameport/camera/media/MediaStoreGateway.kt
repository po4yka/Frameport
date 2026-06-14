package dev.po4yka.frameport.camera.media

import android.os.ParcelFileDescriptor

/**
 * Testability seam over MediaStore ContentResolver operations used by [MediaStoreWriter].
 *
 * The real implementation ([AndroidMediaStoreGateway]) calls ContentResolver + MediaStore APIs.
 * Tests inject a fake that records calls and controls return values without touching the
 * Android framework — no Robolectric required for [MediaStoreWriter] unit tests.
 *
 * All string URIs returned by implementations are MediaStore content:// URIs and must NOT
 * encode any camera-specific identifier (serial, IP, raw filename). The display name passed
 * to [insertPendingRow] must already be validated by [FilenameValidator].
 */
interface MediaStoreGateway {
    /**
     * Inserts a pending media row into the appropriate MediaStore collection with IS_PENDING=1.
     *
     * @param displayName Safe display name (already validated by [FilenameValidator]).
     *   Pattern: "FRP_<handle>.<ext>" — no raw camera filename.
     * @param mimeType MIME type for the row (e.g. "image/jpeg").
     * @param mediaCategory Determines which MediaStore collection to use (Images vs Video).
     * @param relativePath MediaStore RELATIVE_PATH value (e.g. "Pictures/Frameport/2026-06-13").
     *   Must NOT encode camera serial, IP, or user-identifiable data.
     * @return Content URI string for the newly created pending row, or null if insertion failed.
     */
    // NOT cancel-safe: ContentResolver.insert is a blocking call; partial side-effects (row
    // created but not returned) are possible if cancelled mid-call. Caller must handle null.
    fun insertPendingRow(
        displayName: String,
        mimeType: String,
        mediaCategory: MediaCategory,
        relativePath: String,
    ): String?

    /**
     * Opens a write [ParcelFileDescriptor] for the pending row at [uri].
     *
     * Returns null if the URI is no longer valid (e.g. was deleted between insert and open).
     */
    // NOT cancel-safe: ContentResolver.openFileDescriptor is blocking; cancellation mid-call
    // may leave an open fd. Caller owns the returned PFD and must close it.
    fun openWriteFd(uri: String): ParcelFileDescriptor?

    /**
     * Finalises a pending row by setting IS_PENDING=0.
     * Must be called only after the write fd is fully closed and the transfer succeeded.
     *
     * @return true if the row was updated; false if the row no longer exists.
     */
    // NOT cancel-safe: ContentResolver.update is blocking; call from a finally or cleanup path.
    fun finalizePending(uri: String): Boolean

    /**
     * Deletes a pending row from MediaStore.
     * Called on transfer failure or cancellation to prevent orphaned IS_PENDING=1 rows.
     *
     * @return true if the row was deleted; false if it was already absent.
     */
    // NOT cancel-safe: ContentResolver.delete is blocking; call from a finally or cleanup path.
    fun deletePending(uri: String): Boolean
}
