package dev.po4yka.frameport.core.storage.catalog

/**
 * Domain interface for recording and querying the local import catalog.
 *
 * Implementations must honour the privacy-local-first invariant: no raw camera
 * filenames, serials, GPS coordinates, or network identifiers may be stored.
 * All caller-supplied strings should already be redacted before reaching here.
 */
interface ImportCatalog {
    /**
     * Record a successfully imported media object into the local catalog.
     *
     * @param cameraObjectHandle Opaque PTP object handle (integer; not user-identifiable).
     * @param fileNameHash Opaque SHA-256 hash of the original camera filename, or null.
     * @param formatCategory Human-readable format category string (e.g. "jpeg", "raf").
     * @param sizeBytes Transfer size in bytes; null if not reported by the camera.
     * @param mediaStoreUri The MediaStore content:// URI string for the imported file.
     * @param capturedAtEpochMillis UTC millis when the image was captured; null if unknown.
     * @param importedAtEpochMillis UTC millis when this import completed.
     */
    // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
    suspend fun recordImport(
        cameraObjectHandle: Long,
        fileNameHash: String?,
        formatCategory: String,
        sizeBytes: Long?,
        mediaStoreUri: String,
        capturedAtEpochMillis: Long?,
        importedAtEpochMillis: Long,
    )

    /**
     * Returns the [limit] most recently imported entries as [ImportCatalogEntry] records,
     * ordered by import time descending.
     */
    // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
    suspend fun recentImports(limit: Int): List<ImportCatalogEntry>
}

/**
 * Read-only view of a catalog entry returned by [ImportCatalog.recentImports].
 *
 * All fields are redacted: no raw filename, no serial, no GPS.
 */
data class ImportCatalogEntry(
    val localId: Long,
    val cameraObjectHandle: Long,
    val fileNameHash: String?,
    val formatCategory: String,
    val sizeBytes: Long?,
    val mediaStoreUri: String,
    val importStatus: String,
    val capturedAtEpochMillis: Long?,
    val importedAtEpochMillis: Long,
)
