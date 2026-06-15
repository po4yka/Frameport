package dev.po4yka.frameport.core.storage.catalog.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportCatalogDao {
    /**
     * Insert a new [ImportedMediaEntity]. Uses REPLACE on conflict so that a retry
     * of a failed import with the same [cameraObjectHandle] is idempotent (the row
     * is overwritten with fresh status + timestamp).
     *
     * Returns the Row ID of the inserted/replaced row.
     */
    // cancel-safe: Room suspend DAO calls use a dedicated coroutine context internally; cancellation suspends cleanly.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImportedMediaEntity): Long

    /**
     * Returns the most recently imported [limit] entries, ordered by import time descending.
     * Used by the gallery to populate the local-import history.
     */
    // cancel-safe: Room suspend DAO calls use a dedicated coroutine context internally; cancellation suspends cleanly.
    @Query(
        """
        SELECT * FROM imported_media
        ORDER BY imported_at_epoch_millis DESC
        LIMIT :limit
        """,
    )
    suspend fun recentImports(limit: Int): List<ImportedMediaEntity>

    /**
     * Observe all imported media ordered by import time descending.
     *
     * Used by [RoomLocalTimelineStore] to group rows into [ImportSession] records for the
     * local timeline screen. Re-emits on every write to the imported_media table.
     *
     * cancel-safe: Room Flow collection; cancellation unsubscribes the underlying
     * InvalidationTracker observer cleanly.
     */
    @Query("SELECT * FROM imported_media ORDER BY imported_at_epoch_millis DESC")
    fun observeAllImported(): Flow<List<ImportedMediaEntity>>
}
