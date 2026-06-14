package dev.po4yka.frameport.core.storage.catalog.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
}
