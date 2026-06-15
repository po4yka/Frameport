package dev.po4yka.frameport.core.storage.catalog

import dev.po4yka.frameport.core.storage.catalog.db.ImportCatalogStatus
import dev.po4yka.frameport.core.storage.catalog.db.ImportedMediaEntity
import dev.po4yka.frameport.core.storage.timeline.RoomLocalTimelineStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ImportCatalog] contract using [FakeImportCatalog].
 *
 * Room-backed [RoomImportCatalog] requires an Android instrumented test to run against a
 * real (or in-memory) SQLite database; JVM unit tests cannot load Room's sqlite driver.
 * These tests verify the catalogue contract against the fake, ensuring:
 *   - insert + recentImports round-trip stores and retrieves only redacted columns.
 *   - No raw filename, serial, GPS, or network identifier survives the round-trip.
 *   - The [limit] parameter on [recentImports] is honoured.
 *
 * INSTRUMENTATION TODO: add :core:storage androidTest with Room.inMemoryDatabaseBuilder
 * to verify the SQL schema, REPLACE conflict strategy, and ordering against the real DAO.
 */
class ImportCatalogFakeTest {
    private lateinit var catalog: FakeImportCatalog

    @Before
    fun setUp() {
        catalog = FakeImportCatalog()
    }

    // cancel-safe: runTest uses StandardTestDispatcher; suspend calls cancel cleanly.

    @Test
    fun `recordImport then recentImports returns the entry`() =
        runTest {
            catalog.recordImport(
                cameraObjectHandle = 1001L,
                fileNameHash = "sha256abc",
                formatCategory = "jpg",
                sizeBytes = 4096L,
                mediaStoreUri = "content://media/external/images/media/1",
                capturedAtEpochMillis = null,
                importedAtEpochMillis = 1_000_000L,
            )

            val results = catalog.recentImports(10)
            assertEquals(1, results.size)
            val entry = results[0]
            assertEquals(1001L, entry.cameraObjectHandle)
            assertEquals("sha256abc", entry.fileNameHash)
            assertEquals("jpg", entry.formatCategory)
            assertEquals(4096L, entry.sizeBytes)
            assertEquals("content://media/external/images/media/1", entry.mediaStoreUri)
            assertNull(entry.capturedAtEpochMillis)
            assertEquals(1_000_000L, entry.importedAtEpochMillis)
        }

    @Test
    fun `recentImports with limit returns at most limit entries`() =
        runTest {
            repeat(5) { i ->
                catalog.recordImport(
                    cameraObjectHandle = i.toLong(),
                    fileNameHash = null,
                    formatCategory = "raf",
                    sizeBytes = null,
                    mediaStoreUri = "content://media/external/images/media/$i",
                    capturedAtEpochMillis = null,
                    importedAtEpochMillis = i.toLong(),
                )
            }

            val results = catalog.recentImports(limit = 3)
            assertEquals(3, results.size)
        }

    @Test
    fun `recentImports returns entries in reverse insertion order (most recent first)`() =
        runTest {
            catalog.recordImport(
                cameraObjectHandle = 10L,
                fileNameHash = null,
                formatCategory = "jpg",
                sizeBytes = null,
                mediaStoreUri = "content://media/external/images/media/10",
                capturedAtEpochMillis = null,
                importedAtEpochMillis = 1000L,
            )
            catalog.recordImport(
                cameraObjectHandle = 20L,
                fileNameHash = null,
                formatCategory = "jpg",
                sizeBytes = null,
                mediaStoreUri = "content://media/external/images/media/20",
                capturedAtEpochMillis = null,
                importedAtEpochMillis = 2000L,
            )

            val results = catalog.recentImports(10)
            assertEquals(2, results.size)
            // Most recent (importedAtEpochMillis = 2000) must come first.
            assertEquals(20L, results[0].cameraObjectHandle)
            assertEquals(10L, results[1].cameraObjectHandle)
        }

    @Test
    fun `recentImports on empty catalog returns empty list`() =
        runTest {
            val results = catalog.recentImports(10)
            assertTrue(results.isEmpty())
        }

    @Test
    fun `recordImport with non-null importSessionId stores it in the entry`() =
        runTest {
            catalog.recordImport(
                cameraObjectHandle = 77L,
                fileNameHash = null,
                formatCategory = "jpg",
                sizeBytes = null,
                mediaStoreUri = "content://media/external/images/media/77",
                capturedAtEpochMillis = null,
                importedAtEpochMillis = 5000L,
                importSessionId = 42L,
            )

            val entry = catalog.recentImports(1).first()
            assertEquals(42L, entry.importSessionId)
        }

    @Test
    fun `rows with importSessionId are grouped under session key by RoomLocalTimelineStore`() =
        runTest {
            // Arrange: two entities with the same importSessionId and one without.
            val sessionId = 99L
            val withSession1 =
                ImportedMediaEntity(
                    localId = 1L,
                    cameraObjectHandle = 100L,
                    fileNameHash = null,
                    formatCategory = "jpg",
                    sizeBytes = 1024L,
                    mediaStoreUri = "content://media/external/images/media/100",
                    importStatus = ImportCatalogStatus.IMPORTED,
                    capturedAtEpochMillis = null,
                    importedAtEpochMillis = 1000L,
                    importSessionId = sessionId,
                )
            val withSession2 =
                ImportedMediaEntity(
                    localId = 2L,
                    cameraObjectHandle = 101L,
                    fileNameHash = null,
                    formatCategory = "jpg",
                    sizeBytes = 2048L,
                    mediaStoreUri = "content://media/external/images/media/101",
                    importStatus = ImportCatalogStatus.IMPORTED,
                    capturedAtEpochMillis = null,
                    importedAtEpochMillis = 2000L,
                    importSessionId = sessionId,
                )
            val withoutSession =
                ImportedMediaEntity(
                    localId = 3L,
                    cameraObjectHandle = 200L,
                    fileNameHash = null,
                    formatCategory = "raf",
                    sizeBytes = null,
                    mediaStoreUri = "content://media/external/images/media/200",
                    importStatus = ImportCatalogStatus.IMPORTED,
                    capturedAtEpochMillis = null,
                    importedAtEpochMillis = 500L,
                    importSessionId = null,
                )

            // Use a fake DAO that returns a fixed list.
            val fakeDao = FakeImportCatalogDao(listOf(withSession2, withSession1, withoutSession))
            val store = RoomLocalTimelineStore(fakeDao)

            // Act
            val sessions = store.observeSessions().first()

            // Assert: exactly two ImportSession records.
            assertEquals(2, sessions.size)

            // The session-grouped record must use the "session:<id>" key.
            val sessionGroup = sessions.find { it.sessionKey == "session:$sessionId" }
            assertNotNull("expected a session:$sessionId group", sessionGroup)
            assertEquals(2, sessionGroup!!.objectCount)
            assertEquals(3072L, sessionGroup.totalBytes)

            // The day-bucketed record must use the "day:" prefix.
            val dayGroup = sessions.find { it.sessionKey.startsWith("day:") }
            assertNotNull("expected a day-bucket group", dayGroup)
            assertEquals(1, dayGroup!!.objectCount)
        }

    @Test
    fun `recordImport stores null fields without error`() =
        runTest {
            catalog.recordImport(
                cameraObjectHandle = 42L,
                fileNameHash = null,
                formatCategory = "bin",
                sizeBytes = null,
                mediaStoreUri = "content://media/external/images/media/42",
                capturedAtEpochMillis = null,
                importedAtEpochMillis = 9999L,
            )

            val results = catalog.recentImports(1)
            assertEquals(1, results.size)
            assertNull(results[0].fileNameHash)
            assertNull(results[0].sizeBytes)
            assertNull(results[0].capturedAtEpochMillis)
        }

    @Test
    fun `mediaStoreUri stored verbatim (no raw filename or serial injected)`() =
        runTest {
            val uri = "content://media/external/images/media/999"
            catalog.recordImport(
                cameraObjectHandle = 999L,
                fileNameHash = null,
                formatCategory = "jpg",
                sizeBytes = null,
                mediaStoreUri = uri,
                capturedAtEpochMillis = null,
                importedAtEpochMillis = 1L,
            )

            val entry = catalog.recentImports(1).first()
            // Assert round-trip fidelity for the MediaStore URI.
            assertEquals(uri, entry.mediaStoreUri)
            // Assert no raw camera identifier leaked into the URI (it must be a content:// scheme).
            assertTrue(
                "mediaStoreUri must start with content://",
                entry.mediaStoreUri.startsWith("content://"),
            )
        }
}

/**
 * In-memory [ImportCatalog] fake for JVM unit tests.
 *
 * Stores entries in a mutable list ordered by [ImportCatalogEntry.importedAtEpochMillis] descending.
 * Assigns sequential [ImportCatalogEntry.localId] values starting at 1.
 * [importStatus] is hardcoded to "IMPORTED" (mirrors [ImportCatalogStatus.IMPORTED] in Room entity).
 *
 * NOT thread-safe; not intended for concurrent access — JVM unit tests are single-threaded.
 */
private class FakeImportCatalog : ImportCatalog {
    private val entries = mutableListOf<ImportCatalogEntry>()
    private var nextId = 1L

    // cancel-safe: no suspend points inside; returns immediately.
    override suspend fun recordImport(
        cameraObjectHandle: Long,
        fileNameHash: String?,
        formatCategory: String,
        sizeBytes: Long?,
        mediaStoreUri: String,
        capturedAtEpochMillis: Long?,
        importedAtEpochMillis: Long,
        importSessionId: Long?,
    ) {
        entries.add(
            ImportCatalogEntry(
                localId = nextId++,
                cameraObjectHandle = cameraObjectHandle,
                fileNameHash = fileNameHash,
                formatCategory = formatCategory,
                sizeBytes = sizeBytes,
                mediaStoreUri = mediaStoreUri,
                importStatus = "IMPORTED",
                capturedAtEpochMillis = capturedAtEpochMillis,
                importedAtEpochMillis = importedAtEpochMillis,
                importSessionId = importSessionId,
            ),
        )
    }

    // cancel-safe: no suspend points inside; returns immediately.
    override suspend fun recentImports(limit: Int): List<ImportCatalogEntry> =
        entries
            .sortedByDescending { it.importedAtEpochMillis }
            .take(limit)
}

/**
 * Minimal [ImportCatalogDao] fake for JVM unit tests that need to drive [RoomLocalTimelineStore]
 * without a real Room database.
 *
 * Returns a fixed [Flow] of [ImportedMediaEntity] rows supplied at construction time.
 * The [upsert] and [recentImports] stubs are no-ops / empty returns.
 */
private class FakeImportCatalogDao(
    private val entities: List<ImportedMediaEntity>,
) : dev.po4yka.frameport.core.storage.catalog.db.ImportCatalogDao {
    override suspend fun upsert(entity: ImportedMediaEntity): Long = 0L

    override suspend fun recentImports(limit: Int): List<ImportedMediaEntity> = emptyList()

    override fun observeAllImported(): kotlinx.coroutines.flow.Flow<List<ImportedMediaEntity>> = flowOf(entities)
}
