package dev.po4yka.frameport.core.storage.session.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity tracking per-session transfer progress.
 *
 * PRIVACY: no raw filenames, camera serial, SSID, BSSID, MAC, or IP.
 * - [sessionId] is an opaque Long assigned by the session layer (from SessionId.value).
 * - [objectHandle] is the raw PTP object handle (opaque integer; not user-identifiable).
 * - [state] uses the string constants in [SessionProgressState] — never raw PTP codes.
 *
 * Version history:
 *   2 — M10: added for session-progress persistence across process death.
 */
@Entity(tableName = "session_progress")
data class SessionProgressEntity(
    /** Opaque session identifier. PK; one row per active session. */
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    /** Raw PTP object handle being transferred (opaque integer). */
    @ColumnInfo(name = "object_handle")
    val objectHandle: Long,
    /** Bytes transferred so far. */
    @ColumnInfo(name = "bytes_transferred")
    val bytesTransferred: Long,
    /** Total bytes expected; 0 if unknown. */
    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,
    /**
     * Transfer lifecycle state string. Uses constants from [SessionProgressState]
     * (e.g. "in_progress", "completed", "interrupted").
     */
    @ColumnInfo(name = "state")
    val state: String,
    /** UTC epoch millis of the most recent upsert. */
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long,
)

/** State strings persisted in [SessionProgressEntity.state]. */
internal object SessionProgressState {
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
    const val INTERRUPTED = "interrupted"
}
