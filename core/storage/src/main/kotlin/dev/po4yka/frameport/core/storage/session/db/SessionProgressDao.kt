package dev.po4yka.frameport.core.storage.session.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionProgressDao {
    /**
     * Insert or replace the progress row for [entity.sessionId].
     * Called on every [TransferProgress] emission — not on a timer.
     * Returns the Row ID of the upserted row.
     */
    // cancel-safe: Room suspend DAO uses dedicated coroutine context; cancellation propagates cleanly.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionProgressEntity): Long

    /**
     * Returns all rows whose [SessionProgressEntity.state] is [SessionProgressState.IN_PROGRESS].
     * Used at startup to detect interrupted transfers.
     */
    // cancel-safe: Room suspend DAO uses dedicated coroutine context; cancellation propagates cleanly.
    @Query("SELECT * FROM session_progress WHERE state = '${SessionProgressState.IN_PROGRESS}'")
    suspend fun queryInProgress(): List<SessionProgressEntity>

    /**
     * Marks a row as [SessionProgressState.COMPLETED] and updates the timestamp.
     * Idempotent if the row does not exist.
     */
    // cancel-safe: Room suspend DAO uses dedicated coroutine context; cancellation propagates cleanly.
    @Query(
        """
        UPDATE session_progress
        SET state = '${SessionProgressState.COMPLETED}', updated_at_millis = :updatedAtMillis
        WHERE session_id = :sessionId
        """,
    )
    suspend fun markCompleted(
        sessionId: Long,
        updatedAtMillis: Long,
    )

    /**
     * Deletes the progress row for a session. Called after the session closes cleanly.
     */
    // cancel-safe: Room suspend DAO; cancellation propagates cleanly.
    @Query("DELETE FROM session_progress WHERE session_id = :sessionId")
    suspend fun delete(sessionId: Long)
}
