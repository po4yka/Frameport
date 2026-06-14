package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.DiagnosticsRepository
import dev.po4yka.frameport.camera.api.ErrorLayer
import dev.po4yka.frameport.camera.api.defaultCategory
import dev.po4yka.frameport.camera.api.diagnosticEvent
import dev.po4yka.frameport.core.storage.session.ExitReasonDedupStore
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure Kotlin scanner for memory-limiter process kills.
 *
 * No Android framework imports — exercisable with pure-JVM unit tests (no Robolectric).
 * [ExitReasonSource] (Android-specific) maps [ApplicationExitInfo] to [ExitReasonInfo] and
 * provides the [entries] list; this class does not touch the Android API.
 *
 * Algorithm:
 * 1. Filter [entries] to those matching REASON_OTHER (28) with "MemoryLimiter:AnonSwap" in
 *    the description — the Android 17 memory-cap kill signature.
 * 2. For each match, compute the dedup id as "<timestampMillis>_<pid>".
 * 3. Call [ExitReasonDedupStore.recordIfAbsent] — if true (first time seen), record a
 *    [DiagnosticEvent] with a category-only message. If false, skip (already recorded).
 *
 * PRIVACY: no raw PID, serial, IP, or other identifier appears in the [DiagnosticEvent.message].
 * The dedup id contains only numeric values and is stored in the database, not in the message.
 *
 * See G8/G9 and android-foreground-service-lifecycle.md §"Android 17 memory-limiter kill detection".
 */
@Singleton
class MemoryLimiterExitScanner
    @Inject
    constructor(
        private val dedupStore: ExitReasonDedupStore,
        private val diagnosticsRepository: DiagnosticsRepository,
    ) {
        companion object {
            /** [android.app.ApplicationExitInfo.REASON_OTHER] integer value. */
            const val REASON_OTHER_VALUE = 28

            private const val MEMORY_LIMITER_DESCRIPTION_MARKER = "MemoryLimiter:AnonSwap"
        }

        /**
         * Scans [entries] for memory-limiter kills and records new DiagnosticEvents.
         *
         * Idempotent: re-scanning the same list across process restarts never double-records,
         * because [ExitReasonDedupStore.recordIfAbsent] uses a persistent dedup store.
         */
        // cancel-safe: all suspend calls (dedupStore, diagnosticsRepository) propagate
        // cancellation cleanly; no mutable shared state is mutated after cancellation.
        suspend fun scan(entries: List<ExitReasonInfo>) {
            for (entry in entries) {
                if (!isMemoryLimiterKill(entry)) continue

                val id = dedupId(entry)
                val nowMillis = System.currentTimeMillis()
                val isNew = dedupStore.recordIfAbsent(id = id, recordedAtMillis = nowMillis)
                if (isNew) {
                    diagnosticsRepository.recordEvent(
                        diagnosticEvent(
                            layer = ErrorLayer.NativeBoundary,
                            category = defaultCategory(ErrorLayer.NativeBoundary),
                            // Category-only message — no raw PID, serial, IP, or description text.
                            message = "Process terminated by system memory limiter",
                            at = Instant.ofEpochMilli(entry.timestampMillis),
                        ),
                    )
                }
            }
        }

        private fun isMemoryLimiterKill(entry: ExitReasonInfo): Boolean =
            entry.reason == REASON_OTHER_VALUE &&
                entry.description?.contains(MEMORY_LIMITER_DESCRIPTION_MARKER) == true

        /** Deterministic dedup key: "<timestampMillis>_<pid>" — numeric values only. */
        private fun dedupId(entry: ExitReasonInfo): String = "${entry.timestampMillis}_${entry.pid}"
    }
