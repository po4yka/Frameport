package dev.po4yka.frameport.feature.settings

import androidx.datastore.core.DataStoreFactory
import dev.po4yka.frameport.camera.api.CameraMediaFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM unit tests for the [ImportPreferences] typed DataStore backed by [ImportPreferencesSerializer].
 *
 * Uses [DataStoreFactory.create] with a [TemporaryFolder]-scoped file so the real serializer
 * round-trips are exercised in a controlled file system without Android dependencies.
 *
 * Why JVM (not instrumented): DataStore's Serializer contract is pure Kotlin I/O; the
 * CorruptionException / readFrom / writeTo path works identically on the JVM. This avoids
 * the test-APK overhead while still exercising real JSON encoding/decoding.
 *
 * M18: kept typed JSON DataStore per project decision (protobuf plugin intentionally not
 * configured); Proto DataStore deferred.
 *
 * Test structure: Arrange-Act-Assert (AAA).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportPreferencesDataStoreTest {
    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    // A fresh DataStore file per test. DataStore requires a non-existent file on first use so
    // it can write the default value itself; using newFolder() + File(dir, name) avoids the
    // empty-file corruption that newFile() causes (newFile creates a 0-byte file which
    // DataStore tries to deserialize as JSON, producing a JsonDecodingException).
    private fun createDataStore() =
        DataStoreFactory.create(
            serializer = ImportPreferencesSerializer,
            produceFile = { java.io.File(tmpFolder.newFolder(), "import_preferences.json") },
            scope = kotlinx.coroutines.CoroutineScope(testDispatcher),
        )

    // cancel-safe: runTest uses StandardTestDispatcher; DataStore I/O is coroutine-safe.

    // ─── Default values ────────────────────────────────────────────────────────

    @Test
    fun `default preserveOriginalFilename is false`() =
        runTest(testDispatcher) {
            val ds = createDataStore()
            val prefs = ds.data.first()
            assertFalse(prefs.preserveOriginalFilename)
        }

    @Test
    fun `default autoImportOnConnect is false`() =
        runTest(testDispatcher) {
            val ds = createDataStore()
            val prefs = ds.data.first()
            assertFalse(prefs.autoImportOnConnect)
        }

    @Test
    fun `default importPathTemplate is DCIM Frameport date model`() =
        runTest(testDispatcher) {
            val ds = createDataStore()
            val prefs = ds.data.first()
            assertEquals("DCIM/Frameport/{date}/{model}", prefs.importPathTemplate)
        }

    @Test
    fun `default formatFilter is empty`() =
        runTest(testDispatcher) {
            val ds = createDataStore()
            val prefs = ds.data.first()
            assertTrue(prefs.formatFilter.isEmpty())
        }

    // ─── autoImportOnConnect round-trip ───────────────────────────────────────

    @Test
    fun `write autoImportOnConnect true and re-read returns true`() =
        runTest(testDispatcher) {
            val ds = createDataStore()

            // Act
            ds.updateData { it.copy(autoImportOnConnect = true) }
            val result = ds.data.first()

            // Assert
            assertTrue(result.autoImportOnConnect)
        }

    @Test
    fun `write autoImportOnConnect false and re-read returns false`() =
        runTest(testDispatcher) {
            val ds = createDataStore()

            // Arrange: set to true first
            ds.updateData { it.copy(autoImportOnConnect = true) }

            // Act: flip back to false
            ds.updateData { it.copy(autoImportOnConnect = false) }
            val result = ds.data.first()

            // Assert
            assertFalse(result.autoImportOnConnect)
        }

    // ─── preserveOriginalFilename round-trip ──────────────────────────────────

    @Test
    fun `write preserveOriginalFilename true and re-read returns true`() =
        runTest(testDispatcher) {
            val ds = createDataStore()

            // Act
            ds.updateData { it.copy(preserveOriginalFilename = true) }
            val result = ds.data.first()

            // Assert
            assertTrue(result.preserveOriginalFilename)
        }

    @Test
    fun `write preserveOriginalFilename false preserves other fields`() =
        runTest(testDispatcher) {
            val ds = createDataStore()

            // Arrange: set a non-default state
            ds.updateData {
                it.copy(
                    autoImportOnConnect = true,
                    importPathTemplate = "DCIM/Custom/{date}",
                    preserveOriginalFilename = true,
                )
            }

            // Act: flip preserveOriginalFilename to false
            ds.updateData { it.copy(preserveOriginalFilename = false) }
            val result = ds.data.first()

            // Assert: only preserveOriginalFilename changed
            assertTrue(result.autoImportOnConnect)
            assertEquals("DCIM/Custom/{date}", result.importPathTemplate)
            assertFalse(result.preserveOriginalFilename)
        }

    // ─── importPathTemplate round-trip ────────────────────────────────────────

    @Test
    fun `write importPathTemplate and re-read returns same string`() =
        runTest(testDispatcher) {
            val ds = createDataStore()
            val template = "DCIM/Frameport/{model}/{date}"

            // Act
            ds.updateData { it.copy(importPathTemplate = template) }
            val result = ds.data.first()

            // Assert
            assertEquals(template, result.importPathTemplate)
        }

    @Test
    fun `empty importPathTemplate round-trips correctly`() =
        runTest(testDispatcher) {
            val ds = createDataStore()

            // Act
            ds.updateData { it.copy(importPathTemplate = "") }
            val result = ds.data.first()

            // Assert
            assertEquals("", result.importPathTemplate)
        }

    // ─── formatFilter round-trip ──────────────────────────────────────────────

    @Test
    fun `non-empty formatFilter set round-trips through serializer`() =
        runTest(testDispatcher) {
            val ds = createDataStore()
            val filter = setOf(CameraMediaFormat.Jpeg, CameraMediaFormat.Raf)

            // Act
            ds.updateData { it.copy(formatFilter = filter) }
            val result = ds.data.first()

            // Assert
            assertEquals(filter, result.formatFilter)
            assertTrue(result.formatFilter.contains(CameraMediaFormat.Jpeg))
            assertTrue(result.formatFilter.contains(CameraMediaFormat.Raf))
        }

    @Test
    fun `all non-Unknown formats survive a round-trip`() =
        runTest(testDispatcher) {
            val ds = createDataStore()
            val filter =
                setOf(
                    CameraMediaFormat.Jpeg,
                    CameraMediaFormat.Raf,
                    CameraMediaFormat.Heif,
                    CameraMediaFormat.Mov,
                )

            // Act
            ds.updateData { it.copy(formatFilter = filter) }
            val result = ds.data.first()

            // Assert
            assertEquals(filter, result.formatFilter)
            assertEquals(4, result.formatFilter.size)
        }

    @Test
    fun `setting formatFilter to empty clears selection`() =
        runTest(testDispatcher) {
            val ds = createDataStore()

            // Arrange: populate filter first
            ds.updateData { it.copy(formatFilter = setOf(CameraMediaFormat.Jpeg)) }

            // Act: clear it
            ds.updateData { it.copy(formatFilter = emptySet()) }
            val result = ds.data.first()

            // Assert
            assertTrue(result.formatFilter.isEmpty())
        }

    // ─── Backward-compatibility (ignoreUnknownKeys) ───────────────────────────

    @Test
    fun `serializer reads JSON with unknown future fields without throwing`() =
        runTest(testDispatcher) {
            // Simulate a file written by a future app version that added an unknown field.
            // The serializer must silently ignore it (ignoreUnknownKeys = true).
            val jsonWithUnknownField =
                """
                {
                  "autoImportOnConnect": true,
                  "formatFilter": [],
                  "importPathTemplate": "DCIM/Frameport/{date}/{model}",
                  "preserveOriginalFilename": false,
                  "futureField": "some-value-from-future-version"
                }
                """.trimIndent().byteInputStream()

            // Act: use the serializer directly
            val result = ImportPreferencesSerializer.readFrom(jsonWithUnknownField)

            // Assert: known fields parsed correctly, unknown field ignored
            assertTrue(result.autoImportOnConnect)
            assertFalse(result.preserveOriginalFilename)
            assertEquals("DCIM/Frameport/{date}/{model}", result.importPathTemplate)
        }

    @Test
    fun `serializer default value provides safe fallback`() {
        // Verify the serializer's defaultValue matches the data class defaults.
        val default = ImportPreferencesSerializer.defaultValue
        assertFalse(default.autoImportOnConnect)
        assertFalse(default.preserveOriginalFilename)
        assertEquals("DCIM/Frameport/{date}/{model}", default.importPathTemplate)
        assertTrue(default.formatFilter.isEmpty())
    }
}
