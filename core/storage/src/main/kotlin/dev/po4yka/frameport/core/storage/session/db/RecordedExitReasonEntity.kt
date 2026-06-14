package dev.po4yka.frameport.core.storage.session.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for deduplicating ApplicationExitInfo records across re-scans.
 *
 * PRIVACY: [id] is a deterministic "<timestampMillis>_<pid>" string — it contains only
 * numeric values and is not user-identifiable. No raw device identifiers are stored.
 *
 * Version history:
 *   2 — M10: added for idempotent exit-reason scanning (G7/G9).
 */
@Entity(tableName = "recorded_exit_reason")
data class RecordedExitReasonEntity(
    /**
     * Deterministic identifier derived as "<timestampMillis>_<pid>".
     * Used as the PK so re-scanning the same history is idempotent via IGNORE conflict strategy.
     */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    /** UTC epoch millis when this record was first persisted. */
    @ColumnInfo(name = "recorded_at_millis")
    val recordedAtMillis: Long,
)
