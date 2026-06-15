package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.CameraProfile
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.common.Sha256
import dev.po4yka.frameport.core.storage.profile.db.CameraProfileDao
import dev.po4yka.frameport.core.storage.profile.db.CameraProfileEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [CameraProfileRepositoryImpl].
 *
 * Uses [FakeCameraProfileDao] to avoid any Android/Room dependency. Covers the repository's
 * domain-level upsert logic: SHA-256 hashing, profileId stability on re-upsert, lastSeen update,
 * getProfileForCurrentCamera, deleteProfile, and getProfileBySerialHash.
 *
 * Test structure: Arrange-Act-Assert (AAA).
 * Dispatcher: [StandardTestDispatcher] — all coroutines execute on a virtual clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraProfileRepositoryImplTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeDao: FakeCameraProfileDao
    private lateinit var repository: CameraProfileRepositoryImpl

    @Before
    fun setUp() {
        fakeDao = FakeCameraProfileDao()
        repository =
            CameraProfileRepositoryImpl(
                cameraProfileDao = fakeDao,
                ioDispatcher = testDispatcher,
            )
    }

    // cancel-safe: runTest uses StandardTestDispatcher; withContext(ioDispatcher) in the impl
    // is cancel-safe — cancellation propagates to the Room DAO context cleanly.

    // ─── upsertOnSessionOpen — first insert ───────────────────────────────────

    @Test
    fun `upsertOnSessionOpen with new serial inserts a profile`() =
        runTest(testDispatcher) {
            // Act
            repository.upsertOnSessionOpen(
                sessionId = SessionId(1L),
                cameraModel = "XT5",
                firmwareVersion = "1.00",
                rawSerialOrStableId = "raw-serial-001",
                transportCapabilities = 1L,
                compatibilityFlags = 0L,
            )

            // Assert: one entity stored, serialHash is SHA-256 of raw input
            val expectedHash = Sha256.hex("raw-serial-001")
            val stored = fakeDao.getBySerialHash(expectedHash)
            assertNotNull(stored)
            assertEquals("XT5", stored!!.cameraModel)
            assertEquals(expectedHash, stored.serialHash)
        }

    @Test
    fun `upsertOnSessionOpen does NOT store raw serial in the entity`() =
        runTest(testDispatcher) {
            // Act
            repository.upsertOnSessionOpen(
                sessionId = SessionId(1L),
                cameraModel = "XT5",
                firmwareVersion = "1.00",
                rawSerialOrStableId = "secret-serial-xyz",
                transportCapabilities = 1L,
                compatibilityFlags = 0L,
            )

            // Assert: iterate all entities — raw serial must not appear in any field
            val all = fakeDao.allEntities()
            for (entity in all) {
                assertTrue(
                    "Raw serial must not appear in serialHash",
                    entity.serialHash != "secret-serial-xyz",
                )
                assertTrue(
                    "Raw serial must not appear in cameraModel",
                    entity.cameraModel != "secret-serial-xyz",
                )
            }
        }

    // ─── upsertOnSessionOpen — second upsert preserves profileId ─────────────

    @Test
    fun `second upsert with same rawSerial preserves profileId and updates lastSeenEpochMs`() =
        runTest(testDispatcher) {
            // Arrange: first session open
            repository.upsertOnSessionOpen(
                sessionId = SessionId(1L),
                cameraModel = "XT5",
                firmwareVersion = "1.00",
                rawSerialOrStableId = "stable-id-abc",
                transportCapabilities = 1L,
                compatibilityFlags = 0L,
            )
            val hash = Sha256.hex("stable-id-abc")
            val firstEntity = fakeDao.getBySerialHash(hash)!!
            val originalProfileId = firstEntity.profileId
            val originalFirstSeen = firstEntity.firstSeenEpochMs

            // Act: second session open with same raw id, different firmware
            repository.upsertOnSessionOpen(
                sessionId = SessionId(2L),
                cameraModel = "XT5",
                firmwareVersion = "2.00",
                rawSerialOrStableId = "stable-id-abc",
                transportCapabilities = 3L,
                compatibilityFlags = 7L,
            )

            // Assert: profileId and firstSeen are preserved; mutable fields updated
            val updatedEntity = fakeDao.getBySerialHash(hash)!!
            assertEquals("profileId must be stable", originalProfileId, updatedEntity.profileId)
            assertEquals("firstSeenEpochMs must be preserved", originalFirstSeen, updatedEntity.firstSeenEpochMs)
            assertEquals("firmwareVersion updated", "2.00", updatedEntity.firmwareVersion)
            assertEquals("transportCapabilities updated", 3L, updatedEntity.transportCapabilities)
            assertEquals("compatibilityFlags updated", 7L, updatedEntity.compatibilityFlags)
            assertTrue(
                "lastSeenEpochMs must be >= firstSeenEpochMs",
                updatedEntity.lastSeenEpochMs >= updatedEntity.firstSeenEpochMs,
            )
        }

    @Test
    fun `two distinct raw serials produce two distinct profiles`() =
        runTest(testDispatcher) {
            // Act
            repository.upsertOnSessionOpen(
                sessionId = SessionId(1L),
                cameraModel = "XT5",
                firmwareVersion = "1.00",
                rawSerialOrStableId = "camera-body-A",
                transportCapabilities = 1L,
                compatibilityFlags = 0L,
            )
            repository.upsertOnSessionOpen(
                sessionId = SessionId(2L),
                cameraModel = "XT5",
                firmwareVersion = "1.00",
                rawSerialOrStableId = "camera-body-B",
                transportCapabilities = 1L,
                compatibilityFlags = 0L,
            )

            // Assert
            assertEquals(2, fakeDao.allEntities().size)
        }

    // ─── getProfileForCurrentCamera ───────────────────────────────────────────

    @Test
    fun `getProfileForCurrentCamera returns null before any upsert`() {
        // Assert
        assertNull(repository.getProfileForCurrentCamera())
    }

    @Test
    fun `getProfileForCurrentCamera returns profile after upsert`() =
        runTest(testDispatcher) {
            // Act
            repository.upsertOnSessionOpen(
                sessionId = SessionId(1L),
                cameraModel = "XT5",
                firmwareVersion = "1.00",
                rawSerialOrStableId = "raw-for-current",
                transportCapabilities = 1L,
                compatibilityFlags = 0L,
            )

            // Assert
            val current: CameraProfile? = repository.getProfileForCurrentCamera()
            assertNotNull(current)
            assertEquals("XT5", current!!.cameraModel)
            assertEquals(Sha256.hex("raw-for-current"), current.serialHash)
        }

    @Test
    fun `getProfileForCurrentCamera reflects most recent upsert`() =
        runTest(testDispatcher) {
            // Arrange: upsert two different cameras
            repository.upsertOnSessionOpen(
                sessionId = SessionId(1L),
                cameraModel = "XT5",
                firmwareVersion = "1.00",
                rawSerialOrStableId = "cam-first",
                transportCapabilities = 1L,
                compatibilityFlags = 0L,
            )
            repository.upsertOnSessionOpen(
                sessionId = SessionId(2L),
                cameraModel = "XT4",
                firmwareVersion = "2.00",
                rawSerialOrStableId = "cam-second",
                transportCapabilities = 3L,
                compatibilityFlags = 0L,
            )

            // Assert: last upsert wins
            val current = repository.getProfileForCurrentCamera()
            assertEquals("XT4", current?.cameraModel)
        }

    // ─── deleteProfile ────────────────────────────────────────────────────────

    @Test
    fun `deleteProfile removes the profile by profileId`() =
        runTest(testDispatcher) {
            // Arrange
            repository.upsertOnSessionOpen(
                sessionId = SessionId(1L),
                cameraModel = "XT5",
                firmwareVersion = "1.00",
                rawSerialOrStableId = "to-delete",
                transportCapabilities = 1L,
                compatibilityFlags = 0L,
            )
            val hash = Sha256.hex("to-delete")
            val profileId = fakeDao.getBySerialHash(hash)!!.profileId

            // Act
            repository.deleteProfile(profileId)

            // Assert
            assertNull(fakeDao.getBySerialHash(hash))
            assertEquals(0, fakeDao.allEntities().size)
        }

    @Test
    fun `deleteProfile with nonexistent id is a no-op`() =
        runTest(testDispatcher) {
            // Arrange
            repository.upsertOnSessionOpen(
                sessionId = SessionId(1L),
                cameraModel = "XT5",
                firmwareVersion = "1.00",
                rawSerialOrStableId = "survivor",
                transportCapabilities = 1L,
                compatibilityFlags = 0L,
            )

            // Act: delete an id that does not exist
            repository.deleteProfile("nonexistent-profile-id")

            // Assert: the surviving row is untouched
            assertEquals(1, fakeDao.allEntities().size)
        }

    // ─── getProfileBySerialHash ───────────────────────────────────────────────

    @Test
    fun `getProfileBySerialHash returns null for unknown hash`() =
        runTest(testDispatcher) {
            val result = repository.getProfileBySerialHash("no-such-hash")
            assertNull(result)
        }

    @Test
    fun `getProfileBySerialHash returns domain object for known hash`() =
        runTest(testDispatcher) {
            // Arrange
            repository.upsertOnSessionOpen(
                sessionId = SessionId(1L),
                cameraModel = "XT5",
                firmwareVersion = "1.00",
                rawSerialOrStableId = "lookup-test",
                transportCapabilities = 1L,
                compatibilityFlags = 0L,
            )
            val hash = Sha256.hex("lookup-test")

            // Act
            val result: CameraProfile? = repository.getProfileBySerialHash(hash)

            // Assert
            assertNotNull(result)
            assertEquals(hash, result!!.serialHash)
            assertEquals("XT5", result.cameraModel)
        }
}

// ─── Fake ──────────────────────────────────────────────────────────────────────

/**
 * In-memory [CameraProfileDao] fake for JVM unit tests.
 *
 * Stores entities in a [MutableMap] keyed by [CameraProfileEntity.profileId].
 * The unique serial_hash invariant is enforced via [upsert]: a conflict on serial_hash removes
 * the prior row before inserting the new one, matching Room's REPLACE strategy.
 *
 * NOT thread-safe; sufficient for single-threaded test coroutines.
 */
private class FakeCameraProfileDao : CameraProfileDao {
    private val store = mutableMapOf<String, CameraProfileEntity>()

    /** Returns all stored entities as an immutable snapshot for test assertions. */
    fun allEntities(): List<CameraProfileEntity> = store.values.toList()

    // cancel-safe: no suspension points; returns immediately.
    override suspend fun upsert(entity: CameraProfileEntity) {
        // Enforce UNIQUE(serial_hash): remove any existing row with the same serialHash.
        val conflicting = store.values.find { it.serialHash == entity.serialHash && it.profileId != entity.profileId }
        if (conflicting != null) {
            store.remove(conflicting.profileId)
        }
        store[entity.profileId] = entity
    }

    // cancel-safe: no suspension points; returns immediately.
    override suspend fun getBySerialHash(serialHash: String): CameraProfileEntity? =
        store.values.firstOrNull { it.serialHash == serialHash }

    // cancel-safe: Room Flow semantics replicated via MutableStateFlow.
    override fun observeAll(): Flow<List<CameraProfileEntity>> {
        // A simple StateFlow snapshot; only the initial emission is tested via this fake.
        // Full Flow reactivity is tested in the instrumented CameraProfileDaoTest.
        val flow = MutableStateFlow(store.values.sortedByDescending { it.lastSeenEpochMs })
        return flow.asStateFlow()
    }

    // cancel-safe: no suspension points; returns immediately.
    override suspend fun deleteById(profileId: String) {
        store.remove(profileId)
    }
}
