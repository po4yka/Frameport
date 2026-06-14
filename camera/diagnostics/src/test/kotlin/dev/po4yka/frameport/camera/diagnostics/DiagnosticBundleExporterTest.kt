package dev.po4yka.frameport.camera.diagnostics

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dev.po4yka.frameport.camera.api.DiagnosticCategory
import dev.po4yka.frameport.camera.api.ErrorLayer
import dev.po4yka.frameport.camera.api.diagnosticEvent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile

/**
 * Unit tests for [DiagnosticBundleExporter].
 *
 * Uses mockk to satisfy the @ApplicationContext dependency without an Android runtime.
 * cacheDir is redirected to a JVM [TemporaryFolder] — no writes to /sdcard/ or external storage.
 * All events use SYNTHETIC placeholders only.
 *
 * FRAMEPORT_BLESS_GOLDENS=1 is never used here.
 */
class DiagnosticBundleExporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var packageInfo: PackageInfo
    private lateinit var exporter: DiagnosticBundleExporter
    private lateinit var timeline: DiagnosticTimeline

    @Before
    fun setUp() {
        packageInfo = PackageInfo().apply { versionName = "1.0.0-test" }
        packageManager = mockk<PackageManager>()
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } returns packageInfo

        context = mockk<Context>()
        every { context.cacheDir } returns tmp.root
        every { context.packageName } returns "dev.po4yka.frameport.test"
        every { context.packageManager } returns packageManager

        timeline = DiagnosticTimeline()
        exporter = DiagnosticBundleExporter(context)
    }

    // ── zip location ─────────────────────────────────────────────────────────────

    @Test
    fun export_writesZipUnderCacheDir() =
        runTest {
            val result = exporter.export(timeline)

            assertTrue("Expected success", result.isSuccess)
            val file = result.getOrThrow()
            assertTrue("ZIP must be inside cacheDir", file.canonicalPath.startsWith(tmp.root.canonicalPath))
        }

    @Test
    fun export_fileHasZipExtension() =
        runTest {
            val file = exporter.export(timeline).getOrThrow()
            assertTrue("Expected .zip extension", file.name.endsWith(".zip"))
        }

    @Test
    fun export_fileNameContainsDiagnosticsPrefix() =
        runTest {
            val file = exporter.export(timeline).getOrThrow()
            assertTrue(
                "Expected 'frameport-diagnostics-' prefix, got: ${file.name}",
                file.name.startsWith("frameport-diagnostics-"),
            )
        }

    @Test
    fun export_doesNotWriteToExternalStorage() =
        runTest {
            val file = exporter.export(timeline).getOrThrow()
            val path = file.canonicalPath
            assertFalse("Must not write to /sdcard/", path.startsWith("/sdcard/"))
            assertFalse(
                "Must not write to external storage",
                path.contains("external", ignoreCase = true) && !path.contains(tmp.root.canonicalPath),
            )
        }

    // ── zip contents — empty timeline ─────────────────────────────────────────────

    @Test
    fun export_emptyTimeline_producesValidZip() =
        runTest {
            val file = exporter.export(timeline).getOrThrow()
            // ZipFile constructor throws if file is corrupt
            ZipFile(file).use { zip ->
                assertNotNull(zip)
            }
        }

    @Test
    fun export_emptyTimeline_containsManifestEntry() =
        runTest {
            val file = exporter.export(timeline).getOrThrow()
            ZipFile(file).use { zip ->
                assertNotNull("manifest.json must exist", zip.getEntry("manifest.json"))
            }
        }

    @Test
    fun export_emptyTimeline_containsTimelineEntry() =
        runTest {
            val file = exporter.export(timeline).getOrThrow()
            ZipFile(file).use { zip ->
                assertNotNull("timeline.jsonl must exist", zip.getEntry("timeline.jsonl"))
            }
        }

    @Test
    fun export_emptyTimeline_manifestEventCountIsZero() =
        runTest {
            val file = exporter.export(timeline).getOrThrow()
            val manifest = readZipEntry(file, "manifest.json")
            assertTrue("Expected eventCount:0, got: $manifest", manifest.contains("\"eventCount\":0"))
        }

    @Test
    fun export_emptyTimeline_timelineJsonlIsEmpty() =
        runTest {
            val file = exporter.export(timeline).getOrThrow()
            val jsonl = readZipEntry(file, "timeline.jsonl")
            assertEquals("timeline.jsonl must be empty for zero events", "", jsonl.trim())
        }

    // ── zip contents — manifest fields ───────────────────────────────────────────

    @Test
    fun export_manifestSchemaVersionIsOne() =
        runTest {
            appendSyntheticEvent("Diagnostic check passed")
            val file = exporter.export(timeline).getOrThrow()
            val manifest = readZipEntry(file, "manifest.json")
            assertTrue("Expected schemaVersion:1, got: $manifest", manifest.contains("\"schemaVersion\":1"))
        }

    @Test
    fun export_manifestContainsAppVersion() =
        runTest {
            val file = exporter.export(timeline).getOrThrow()
            val manifest = readZipEntry(file, "manifest.json")
            assertTrue(
                "Expected appVersion from packageManager, got: $manifest",
                manifest.contains("\"appVersion\":\"1.0.0-test\""),
            )
        }

    @Test
    fun export_manifestContainsExportedAt() =
        runTest {
            val file = exporter.export(timeline).getOrThrow()
            val manifest = readZipEntry(file, "manifest.json")
            assertTrue("Expected exportedAt field, got: $manifest", manifest.contains("\"exportedAt\":"))
        }

    // ── zip contents — timeline with events ──────────────────────────────────────

    @Test
    fun export_singleEvent_jsonlHasOneLine() =
        runTest {
            appendSyntheticEvent("Import completed")
            val file = exporter.export(timeline).getOrThrow()
            val lines = nonEmptyLines(readZipEntry(file, "timeline.jsonl"))
            assertEquals("Expected 1 JSONL line for 1 event", 1, lines.size)
        }

    @Test
    fun export_singleEvent_manifestEventCountIsOne() =
        runTest {
            appendSyntheticEvent("Import completed")
            val file = exporter.export(timeline).getOrThrow()
            val manifest = readZipEntry(file, "manifest.json")
            assertTrue("Expected eventCount:1, got: $manifest", manifest.contains("\"eventCount\":1"))
        }

    @Test
    fun export_multipleEvents_jsonlLineCountMatchesEventCount() =
        runTest {
            appendSyntheticEvent("Session opened")
            appendSyntheticEvent("Transfer started")
            appendSyntheticEvent("Transfer completed")
            val file = exporter.export(timeline).getOrThrow()
            val lines = nonEmptyLines(readZipEntry(file, "timeline.jsonl"))
            assertEquals("Expected 3 JSONL lines for 3 events", 3, lines.size)
        }

    @Test
    fun export_multipleEvents_manifestEventCountMatches() =
        runTest {
            appendSyntheticEvent("Session opened")
            appendSyntheticEvent("Transfer started")
            appendSyntheticEvent("Transfer completed")
            val file = exporter.export(timeline).getOrThrow()
            val manifest = readZipEntry(file, "manifest.json")
            assertTrue("Expected eventCount:3, got: $manifest", manifest.contains("\"eventCount\":3"))
        }

    @Test
    fun export_eventJsonContainsLayerField() =
        runTest {
            appendSyntheticEvent("Import completed")
            val file = exporter.export(timeline).getOrThrow()
            val jsonl = readZipEntry(file, "timeline.jsonl")
            assertTrue("Expected layer field in event JSON, got: $jsonl", jsonl.contains("\"layer\":"))
        }

    @Test
    fun export_eventJsonContainsMessageField() =
        runTest {
            appendSyntheticEvent("Import completed")
            val file = exporter.export(timeline).getOrThrow()
            val jsonl = readZipEntry(file, "timeline.jsonl")
            assertTrue(
                "Expected message field in event JSON, got: $jsonl",
                jsonl.contains("\"message\":\"Import completed\""),
            )
        }

    @Test
    fun export_eventJsonContainsTimestampField() =
        runTest {
            appendSyntheticEvent("Session opened")
            val file = exporter.export(timeline).getOrThrow()
            val jsonl = readZipEntry(file, "timeline.jsonl")
            assertTrue("Expected timestamp field in event JSON, got: $jsonl", jsonl.contains("\"timestamp\":"))
        }

    // ── packageManager fallback ───────────────────────────────────────────────────

    @Test
    fun export_packageManagerThrows_manifestAppVersionIsUnknown() =
        runTest {
            every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws
                android.content.pm.PackageManager
                    .NameNotFoundException()
            val file = exporter.export(timeline).getOrThrow()
            val manifest = readZipEntry(file, "manifest.json")
            assertTrue(
                "Expected appVersion:unknown on NameNotFoundException, got: $manifest",
                manifest.contains("\"appVersion\":\"unknown\""),
            )
        }

    @Test
    fun export_packageManagerReturnsNullVersionName_manifestAppVersionIsUnknown() =
        runTest {
            packageInfo.versionName = null
            val file = exporter.export(timeline).getOrThrow()
            val manifest = readZipEntry(file, "manifest.json")
            assertTrue(
                "Expected appVersion:unknown when versionName is null, got: $manifest",
                manifest.contains("\"appVersion\":\"unknown\""),
            )
        }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private fun appendSyntheticEvent(message: String) {
        timeline.append(
            diagnosticEvent(
                layer = ErrorLayer.MediaTransfer,
                category = DiagnosticCategory.MediaTransferEvent,
                message = message,
                sessionId = "session-test-001",
                at = Instant.parse("2024-01-15T10:30:00Z"),
            ),
        )
    }

    private fun readZipEntry(
        file: File,
        entryName: String,
    ): String =
        ZipFile(file).use { zip ->
            val entry =
                zip.getEntry(entryName)
                    ?: error("Entry '$entryName' not found in ${file.name}")
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
        }

    private fun nonEmptyLines(text: String): List<String> = text.lines().filter { it.isNotBlank() }

    private fun assertFalse(
        message: String,
        condition: Boolean,
    ) {
        assertTrue(message, !condition)
    }
}
