package dev.po4yka.frameport.core.storage.session.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy

@Dao
interface ExitReasonDao {
    /**
     * Inserts a [RecordedExitReasonEntity] using IGNORE conflict strategy.
     *
     * Returns the Row ID of the newly inserted row, or -1L if the row already existed
     * (i.e., this exit-reason id was previously recorded). Callers can use the return value
     * to determine whether this is a new record: `rowId != -1L`.
     *
     * This is the dedup gate: re-scanning the same [ApplicationExitInfo] history across
     * process restarts never double-records a DiagnosticEvent. See G8/G9.
     */
    // cancel-safe: Room suspend DAO uses dedicated coroutine context; cancellation propagates cleanly.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: RecordedExitReasonEntity): Long
}
