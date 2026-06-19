package dev.po4yka.frameport.core.storage.profile.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraProfileDao {
    /**
     * Insert or replace a [CameraProfileEntity] by primary key (profile_id).
     *
     * INTERNAL — use [upsertBySerialHash] for all callers outside this package.
     * Direct use of this method is unsafe: a caller that supplies a fresh profileId with a
     * serial_hash that already exists in the table triggers the UNIQUE constraint on
     * serial_hash, which REPLACE resolves by deleting the old row and inserting the new one.
     * That silently destroys [CameraProfileEntity.firstSeenEpochMs] on the old row.
     *
     * cancel-safe: Room suspend DAO calls use a dedicated coroutine context; cancellation
     * suspends cleanly.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CameraProfileEntity)

    /**
     * Look up a profile by its [serialHash].
     * Returns null when no matching row exists.
     *
     * cancel-safe: Room suspend DAO call; cancellation suspends cleanly.
     */
    @Query("SELECT * FROM camera_profile WHERE serial_hash = :serialHash LIMIT 1")
    suspend fun getBySerialHash(serialHash: String): CameraProfileEntity?

    /**
     * Atomic read-then-upsert of a camera profile identified by [serialHash].
     *
     * This is the only public write path. Runs the getBySerialHash + upsert pair inside a
     * single SQLite transaction so that concurrent callers cannot both read null and
     * independently insert duplicate rows with different profileIds (which would trigger the
     * UNIQUE constraint on serial_hash, causing REPLACE to delete the older row and silently
     * destroy its firstSeenEpochMs).
     *
     * [buildEntity] receives the existing [CameraProfileEntity] (or null) and must return
     * the entity to persist. The caller is responsible for preserving profileId and
     * firstSeenEpochMs when the existing row is non-null.
     *
     * cancel-safe: Room @Transaction wraps the two suspend DAO calls in a single SQLite
     * transaction; cancellation between the inner calls would roll back the transaction,
     * leaving the database in a consistent state.
     */
    @Transaction
    suspend fun upsertBySerialHash(
        serialHash: String,
        buildEntity: (existing: CameraProfileEntity?) -> CameraProfileEntity,
    ) {
        val existing = getBySerialHash(serialHash)
        upsert(buildEntity(existing))
    }

    /**
     * Observe all camera profiles ordered by most-recently-seen first.
     * The returned [Flow] re-emits on every write to the table.
     *
     * cancel-safe: Room Flow collection; cancellation unsubscribes the underlying
     * InvalidationTracker observer cleanly.
     */
    @Query("SELECT * FROM camera_profile ORDER BY last_seen_epoch_ms DESC")
    fun observeAll(): Flow<List<CameraProfileEntity>>

    /**
     * Delete a profile by its primary key [profileId].
     * No-op when no matching row exists.
     *
     * cancel-safe: Room suspend DAO call; cancellation suspends cleanly.
     */
    @Query("DELETE FROM camera_profile WHERE profile_id = :profileId")
    suspend fun deleteById(profileId: String)
}
