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
     * Observe the most-recently-imported [TIMELINE_LIMIT] rows, ordered by import time descending.
     *
     * Used by [RoomLocalTimelineStore] to group rows into [ImportSession] records for the
     * local timeline screen. Re-emits on every write to the imported_media table.
     *
     * The [TIMELINE_LIMIT] cap (500) is intentional: the in-memory grouping in
     * [RoomLocalTimelineStore] runs on every emission, so an unbounded load would grow
     * proportionally with the catalog. 500 rows covers roughly two years of daily Fujifilm
     * shoots (typical burst sessions of 10-50 files) and keeps the per-emission allocation
     * bounded. Pagination or cursor-based loading is deferred to a future milestone.
     *
     * cancel-safe: Room Flow collection; cancellation unsubscribes the underlying
     * InvalidationTracker observer cleanly.
     */
    @Query(
        """
        SELECT * FROM imported_media
        ORDER BY imported_at_epoch_millis DESC
        LIMIT $TIMELINE_LIMIT
        """,
    )
    fun observeAllImported(): Flow<List<ImportedMediaEntity>>

    companion object {
        /** Maximum number of rows loaded per timeline emission. See [observeAllImported]. */
        const val TIMELINE_LIMIT = 500
    }
}
