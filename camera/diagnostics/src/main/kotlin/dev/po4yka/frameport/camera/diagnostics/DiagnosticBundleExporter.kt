package dev.po4yka.frameport.camera.diagnostics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports a diagnostic bundle as a ZIP file to [Context.cacheDir].
 *
 * The bundle contains:
 *  - `manifest.json`: schema version, app version, export timestamp, event count.
 *  - `timeline.jsonl`: one JSON object per [dev.po4yka.frameport.camera.api.DiagnosticEvent]
 *    in [DiagnosticTimeline.timeline].value at the moment of export.
 *
 * PRIVACY INVARIANTS:
 *  - Output is written ONLY to [Context.cacheDir] — never to external storage,
 *    /sdcard/, or any world-readable path.
 *  - App version is read from [Context.packageManager] — NOT BuildConfig, which is
 *    unavailable in library modules.
 *  - No network calls, no upload path. This class is entirely local.
 *  - Events must have been pre-redacted by [DiagnosticCollector] before reaching
 *    [DiagnosticTimeline]; this exporter does not perform additional redaction.
 *
 * Schema version: 1 (literal integer in manifest.json).
 */
@Singleton
class DiagnosticBundleExporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val isoFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

        /**
         * Exports the current [timeline] snapshot to a ZIP file in [Context.cacheDir].
         *
         * @param timeline The [DiagnosticTimeline] whose [DiagnosticTimeline.timeline].value
         *   snapshot is written into the bundle.
         * @return [Result.success] with the ZIP [File], or [Result.failure] with the
         *   underlying [Throwable] on any I/O or serialisation error.
         */
        // cancel-safe: suspend only for dispatcher switching in callers; the body itself uses
        // blocking I/O which must be called on an IO dispatcher by the caller. The file write
        // is not atomically rolled back on cancellation, but cacheDir files are ephemeral and
        // a partial ZIP will fail to open, making the failure observable.
        suspend fun export(timeline: DiagnosticTimeline): Result<File> =
            runCatching {
                val events = timeline.timeline.value
                val exportedAt = Instant.now()
                val fileName = "frameport-diagnostics-${isoFormatter.format(exportedAt)}.zip"
                val outFile = File(context.cacheDir, fileName)

                val appVersion =
                    try {
                        context.packageManager
                            .getPackageInfo(context.packageName, 0)
                            .versionName
                            ?: "unknown"
                    } catch (_: Exception) {
                        "unknown"
                    }

                ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
                    // ── manifest.json ────────────────────────────────────────────
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    val manifest =
                        buildManifestJson(
                            schemaVersion = 1,
                            appVersion = appVersion,
                            exportedAt = isoFormatter.format(exportedAt),
                            eventCount = events.size,
                        )
                    zip.write(manifest.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    // ── timeline.jsonl ───────────────────────────────────────────
                    zip.putNextEntry(ZipEntry("timeline.jsonl"))
                    events.forEach { event ->
                        val line = buildEventJson(event) + "\n"
                        zip.write(line.toByteArray(Charsets.UTF_8))
                    }
                    zip.closeEntry()
                }

                Timber.d("DiagnosticBundle exported: eventCount=%d", events.size)
                outFile
            }

        // ── JSON builders (manual — no serialisation dep required) ────────────────

        private fun buildManifestJson(
            schemaVersion: Int,
            appVersion: String,
            exportedAt: String,
            eventCount: Int,
        ): String =
            buildString {
                append('{')
                append("\"schemaVersion\":").append(schemaVersion).append(',')
                append("\"appVersion\":").append(jsonString(appVersion)).append(',')
                append("\"exportedAt\":").append(jsonString(exportedAt)).append(',')
                append("\"eventCount\":").append(eventCount)
                append('}')
            }

        private fun buildEventJson(event: dev.po4yka.frameport.camera.api.DiagnosticEvent): String =
            buildString {
                append('{')
                append("\"timestamp\":").append(jsonString(isoFormatter.format(event.timestamp))).append(',')
                append("\"layer\":").append(jsonString(event.layer.name)).append(',')
                append("\"category\":").append(jsonString(event.category::class.simpleName ?: "Unknown")).append(',')
                append("\"message\":").append(jsonString(event.message)).append(',')
                append("\"sessionId\":").append(jsonString(event.sessionId)).append(',')
                append("\"metadata\":{")
                event.metadata.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) append(',')
                    append(jsonString(k)).append(':').append(jsonString(v))
                }
                append('}')
                append('}')
            }

        /**
         * Minimal JSON string escaping: backslash, double-quote, and ASCII control chars.
         * Sufficient for the diagnostic payload (layer names, timestamps, redacted messages).
         */
        private fun jsonString(value: String): String =
            buildString {
                append('"')
                value.forEach { c ->
                    when (c) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                    }
                }
                append('"')
            }
    }
