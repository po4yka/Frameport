package dev.po4yka.frameport.core.storage.profile.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.frameport.core.storage.catalog.db.FrameportDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [CameraProfileDao] using an in-memory [FrameportDatabase].
 *
 * Why instrumented: Room's SQLite driver is an Android native library; JVM unit tests cannot
 * load it without Robolectric. These tests target a real in-process SQLite database so schema
 * constraints (UNIQUE index on serial_hash, CASCADE behaviour) are exercised against the real
 * engine.
 *
 * Test structure: Arrange-Act-Assert (AAA).
 * Each test starts with an empty in-memory database and tears it down in [tearDown].
 */
@RunWith(AndroidJUnit4::class)
class CameraProfileDaoTest {
    private lateinit var db: FrameportDatabase
    private lateinit var dao: CameraProfileDao

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    FrameportDatabase::class.java,
                )
                // Allow main-thread queries in tests — instrumented tests run on a looper thread
                // where the Room restriction applies. Allowing it avoids needing a real coroutine
                // dispatcher setup at the DB-builder level.
                .allowMainThreadQueries()
                .build()
        dao = db.cameraProfileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // cancel-safe: runTest uses StandardTestDispatcher; Room suspend DAOs are cancel-safe.

    // ─── upsert (internal — tested here as same package) ──────────────────────

    @Test
    fun upsert_insertsNewProfile_andGetBySerialHashReturnsIt() =
        runTest {
            // Arrange
            val entity = buildEntity(profileId = "p1", serialHash = "hash-abc")

            // Act
            dao.upsert(entity)
            val result = dao.getBySerialHash("hash-abc")

            // Assert
            assertNotNull(result)
            assertEquals("p1", result!!.profileId)
            assertEquals("hash-abc", result.serialHash)
            assertEquals("XT5", result.cameraModel)
        }

    @Test
    fun upsert_withSameSerialHash_replacesRow_preservingNewValues() =
        runTest {
            // Arrange: first insert
            dao.upsert(buildEntity(profileId = "p1", serialHash = "hash-abc", lastSeenEpochMs = 1000L))

            // Act: second upsert with same serial hash but different profileId and lastSeen
            dao.upsert(buildEntity(profileId = "p1", serialHash = "hash-abc", lastSeenEpochMs = 2000L))
            val result = dao.getBySerialHash("hash-abc")

            // Assert: row exists, lastSeenEpochMs is updated
            assertNotNull(result)
            assertEquals(2000L, result!!.lastSeenEpochMs)
        }

    @Test
    fun upsert_twoDistinctSerialHashes_insertsTwoRows() =
        runTest {
            // Arrange
            dao.upsert(buildEntity(profileId = "p1", serialHash = "hash-aaa"))
            dao.upsert(buildEntity(profileId = "p2", serialHash = "hash-bbb"))

            // Act
            val all = dao.observeAll().first()

            // Assert
            assertEquals(2, all.size)
        }

    // ─── upsertBySerialHash — firstSeenEpochMs preservation ──────────────────

    /**
     * H-8 regression: [CameraProfileDao.upsertBySerialHash] MUST preserve firstSeenEpochMs
     * when a row already exists for the given serial hash.
     *
     * Scenario: camera is seen for the first time (firstSeen = 1000), then seen again on a
     * second session (firstSeen would be 9999 if supplied naively). The second
     * upsertBySerialHash call must keep firstSeen = 1000.
     */
    @Test
    fun upsertBySerialHash_preservesFirstSeenEpochMs_onSubsequentUpsert() =
        runTest {
            // Arrange: first session open — camera seen for the first time
            val firstSeenMs = 1_000L
            dao.upsertBySerialHash("hash-xt5") { existing ->
                buildEntity(
                    profileId = "p1",
                    serialHash = "hash-xt5",
                    firstSeenEpochMs = firstSeenMs,
                    lastSeenEpochMs = firstSeenMs,
                )
            }

            // Act: second session open — must not overwrite firstSeenEpochMs
            val secondSeenMs = 9_999L
            dao.upsertBySerialHash("hash-xt5") { existing ->
                // Correct pattern: preserve firstSeenEpochMs from existing row
                buildEntity(
                    profileId = existing!!.profileId,
                    serialHash = "hash-xt5",
                    firstSeenEpochMs = existing.firstSeenEpochMs,
                    lastSeenEpochMs = secondSeenMs,
                )
            }

            // Assert
            val result = dao.getBySerialHash("hash-xt5")
            assertNotNull(result)
            assertEquals(
                "firstSeenEpochMs must be preserved from the original insert",
                firstSeenMs,
                result!!.firstSeenEpochMs,
            )
            assertEquals(
                "lastSeenEpochMs must be updated to the second session time",
                secondSeenMs,
                result.lastSeenEpochMs,
            )
        }

    /**
     * Documents the REPLACE hazard: calling the internal [CameraProfileDao.upsert] directly
     * with a new profileId but the same serial_hash as an existing row triggers the UNIQUE
     * index conflict resolution strategy REPLACE. SQLite resolves the conflict by deleting the
     * old row (including its firstSeenEpochMs) and inserting the new one. This is why [upsert]
     * is documented as internal and [upsertBySerialHash] is the mandatory public write path.
     *
     * This test documents the known-bad behaviour rather than asserting it is fixed —
     * it exists so reviewers understand exactly what REPLACE does to firstSeenEpochMs when
     * called with a mismatched profileId.
     */
    @Test
    fun upsert_rawReplace_withDifferentProfileId_destroysFirstSeenEpochMs_documentedBehaviour() =
        runTest {
            // Arrange: original row with firstSeenEpochMs = 1000
            val originalFirstSeen = 1_000L
            dao.upsert(
                buildEntity(
                    profileId = "original-id",
                    serialHash = "hash-replace-hazard",
                    firstSeenEpochMs = originalFirstSeen,
                    lastSeenEpochMs = originalFirstSeen,
                ),
            )

            // Act: raw upsert with a NEW profileId but the SAME serial_hash.
            // REPLACE deletes the old row (losing firstSeenEpochMs = 1000) and inserts this one.
            val replacedFirstSeen = 9_999L
            dao.upsert(
                buildEntity(
                    profileId = "replacement-id", // different PK
                    serialHash = "hash-replace-hazard", // same serial_hash → REPLACE fires
                    firstSeenEpochMs = replacedFirstSeen, // original value LOST
                    lastSeenEpochMs = replacedFirstSeen,
                ),
            )

            // Assert: the row exists but firstSeenEpochMs is the replacement value, not original.
            // This documents the data-loss bug that upsertBySerialHash prevents.
            val result = dao.getBySerialHash("hash-replace-hazard")
            assertNotNull(result)
            assertEquals(
                "REPLACE with different profileId LOSES the original firstSeenEpochMs — " +
                    "this is the documented hazard that makes raw upsert() dangerous",
                replacedFirstSeen,
                result!!.firstSeenEpochMs,
            )
        }

    // ─── observeAll ───────────────────────────────────────────────────────────

    @Test
    fun observeAll_onEmptyTable_emitsEmptyList() =
        runTest {
            // Act
            val result = dao.observeAll().first()

            // Assert
            assertEquals(emptyList<CameraProfileEntity>(), result)
        }

    @Test
    fun observeAll_afterInsert_emitsUpdatedList() =
        runTest {
            // Arrange
            dao.upsert(buildEntity(profileId = "p1", serialHash = "hash-1", lastSeenEpochMs = 500L))
            dao.upsert(buildEntity(profileId = "p2", serialHash = "hash-2", lastSeenEpochMs = 1000L))

            // Act: first emission after both inserts
            val result = dao.observeAll().first()

            // Assert: ordered by last_seen_epoch_ms DESC
            assertEquals(2, result.size)
            assertEquals("p2", result[0].profileId)
            assertEquals("p1", result[1].profileId)
        }

    @Test
    fun observeAll_orderedByLastSeenEpochMsDescending() =
        runTest {
            // Arrange: insert three profiles with different lastSeenEpochMs values
            dao.upsert(buildEntity(profileId = "old", serialHash = "hash-old", lastSeenEpochMs = 100L))
            dao.upsert(buildEntity(profileId = "newest", serialHash = "hash-new", lastSeenEpochMs = 9000L))
            dao.upsert(buildEntity(profileId = "mid", serialHash = "hash-mid", lastSeenEpochMs = 500L))

            // Act
            val result = dao.observeAll().first()

            // Assert: newest first
            assertEquals(listOf("newest", "mid", "old"), result.map { it.profileId })
        }

    // ─── getBySerialHash ──────────────────────────────────────────────────────

    @Test
    fun getBySerialHash_nonExistentHash_returnsNull() =
        runTest {
            // Act
            val result = dao.getBySerialHash("does-not-exist")

            // Assert
            assertNull(result)
        }

    @Test
    fun getBySerialHash_afterDelete_returnsNull() =
        runTest {
            // Arrange
            dao.upsert(buildEntity(profileId = "p1", serialHash = "hash-del"))

            // Act
            dao.deleteById("p1")
            val result = dao.getBySerialHash("hash-del")

            // Assert
            assertNull(result)
        }

    // ─── deleteById ───────────────────────────────────────────────────────────

    @Test
    fun deleteById_removesExactlyOneRow() =
        runTest {
            // Arrange
            dao.upsert(buildEntity(profileId = "p1", serialHash = "hash-1"))
            dao.upsert(buildEntity(profileId = "p2", serialHash = "hash-2"))

            // Act
            dao.deleteById("p1")
            val all = dao.observeAll().first()

            // Assert
            assertEquals(1, all.size)
            assertEquals("p2", all[0].profileId)
        }

    @Test
    fun deleteById_nonExistentId_isNoOp() =
        runTest {
            // Arrange
            dao.upsert(buildEntity(profileId = "p1", serialHash = "hash-1"))

            // Act: delete a profile that does not exist
            dao.deleteById("nonexistent-id")
            val all = dao.observeAll().first()

            // Assert: the existing row is untouched
            assertEquals(1, all.size)
        }

    // ─── unique index behaviour ────────────────────────────────────────────────

    @Test
    fun uniqueSerialHashIndex_secondInsertWithSameHash_resultsInSingleRow() =
        runTest {
            // Arrange
            dao.upsert(buildEntity(profileId = "p1", serialHash = "collision-hash"))

            // Act: upsert with same serial_hash but different profile_id triggers REPLACE
            dao.upsert(buildEntity(profileId = "p1-updated", serialHash = "collision-hash", cameraModel = "XT4"))
            val all = dao.observeAll().first()

            // Assert: exactly one row remains (the replace eliminated the duplicate)
            assertEquals(1, all.size)
            assertEquals("XT4", all[0].cameraModel)
        }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Build a [CameraProfileEntity] with sensible defaults for tests. */
    private fun buildEntity(
        profileId: String,
        serialHash: String,
        cameraModel: String = "XT5",
        firmwareVersion: String = "1.00",
        transportCapabilities: Long = 1L,
        compatibilityFlags: Long = 0L,
        firstSeenEpochMs: Long = 1_000L,
        lastSeenEpochMs: Long = 1_000L,
        notes: String? = null,
    ) = CameraProfileEntity(
        profileId = profileId,
        cameraModel = cameraModel,
        firmwareVersion = firmwareVersion,
        serialHash = serialHash,
        transportCapabilities = transportCapabilities,
        compatibilityFlags = compatibilityFlags,
        firstSeenEpochMs = firstSeenEpochMs,
        lastSeenEpochMs = lastSeenEpochMs,
        notes = notes,
    )
}
