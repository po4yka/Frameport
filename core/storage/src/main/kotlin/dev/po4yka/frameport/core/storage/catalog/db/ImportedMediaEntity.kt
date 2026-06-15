package dev.po4yka.frameport.core.storage.catalog.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the imported-media catalog.
 *
 * PRIVACY: every column is redacted per privacy-local-first.md:
 * - No raw camera filename, path, SSID, BSSID, MAC, serial, or IP.
 * - [cameraObjectHandle] is the raw PTP object handle (an opaque integer; not user-identifiable).
 * - [fileNameHash] is an opaque one-way SHA-256 of the original camera filename (if supplied by
 *   camera:api). Null if the field was absent on the [CameraMediaObject].
 * - [formatCategory] is a human-readable media category string derived from [CameraMediaFormat]
 *   (e.g. "jpeg", "raf", "heif", "mov", "unknown") — never a raw PTP code.
 * - [mediaStoreUri] is the content:// URI assigned by MediaStore; it contains no camera-specific
 *   identifiers.
 * - No GPS/location columns.
 */
@Entity(
    tableName = "imported_media",
    // Unique on the camera object handle so a re-import of the same object REPLACEs the prior
    // row (per [ImportCatalogDao.upsert]) instead of accumulating duplicate rows. Without this
    // index the autoGenerate primary key never collides and REPLACE deduplicates nothing.
    indices = [Index(value = ["camera_object_handle"], unique = true)],
)
data class ImportedMediaEntity(
    /** Auto-generated local primary key. Not exposed outside :core:storage. */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "local_id")
    val localId: Long = 0,
    /** Raw PTP object handle from the camera (opaque integer; not user-identifiable). */
    @ColumnInfo(name = "camera_object_handle")
    val cameraObjectHandle: Long,
    /**
     * Opaque SHA-256 hash of the original camera filename, if available.
     * NEVER store or log the raw filename.
     */
    @ColumnInfo(name = "file_name_hash")
    val fileNameHash: String?,
    /**
     * Human-readable media format category (e.g. "jpeg", "raf", "heif", "mov", "unknown").
     * Derived from [CameraMediaFormat]; never a raw PTP numeric code.
     */
    @ColumnInfo(name = "format_category")
    val formatCategory: String,
    /** Transfer size in bytes; null if the camera did not report it. */
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long?,
    /**
     * MediaStore content:// URI for the imported file.
     * Contains no camera-specific identifiers.
     */
    @ColumnInfo(name = "mediastore_uri")
    val mediaStoreUri: String,
    /**
     * Import lifecycle status string. Uses the string representation of
     * [ImportCatalogStatus] (e.g. "imported", "failed", "cancelled").
     */
    @ColumnInfo(name = "import_status")
    val importStatus: String,
    /** UTC epoch millis when the image was captured on camera; null if not reported. */
    @ColumnInfo(name = "captured_at_epoch_millis")
    val capturedAtEpochMillis: Long?,
    /** UTC epoch millis when the import completed in Frameport. */
    @ColumnInfo(name = "imported_at_epoch_millis")
    val importedAtEpochMillis: Long,
    /**
     * Optional link to the camera session during which this object was imported.
     * Null for rows inserted before M18 or when session tracking is unavailable.
     * Used by [RoomLocalTimelineStore] to group imports into [ImportSession] records.
     *
     * M18: populated by the import pipeline when a session id is available; pre-M18
     * rows retain null and are bucketed by calendar day instead.
     */
    @ColumnInfo(name = "import_session_id")
    val importSessionId: Long? = null,
)

/** Status strings persisted in [ImportedMediaEntity.importStatus]. */
internal object ImportCatalogStatus {
    const val IMPORTED = "imported"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}
