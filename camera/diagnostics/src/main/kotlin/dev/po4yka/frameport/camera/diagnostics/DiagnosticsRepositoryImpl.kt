package dev.po4yka.frameport.camera.diagnostics

import dev.po4yka.frameport.camera.api.DiagnosticEvent
import dev.po4yka.frameport.camera.api.DiagnosticsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [DiagnosticsRepository].
 *
 * Bridges the [DiagnosticsRepository] API contract (in :camera:api) to the
 * [DiagnosticCollector] + [DiagnosticTimeline] machinery in :camera:diagnostics.
 *
 *  - [events] delegates to [DiagnosticTimeline.live] — callers receive events
 *    emitted while they are actively collecting (replay=0).
 *  - [recordEvent] delegates to [DiagnosticCollector.record], which forwards to
 *    [DiagnosticTimeline.append] and emits a redacted Timber log entry.
 *
 * PRIVACY: this class performs no redaction. Pre-redacted [DiagnosticEvent] instances
 * must be passed to [recordEvent]; see [DiagnosticCollector] and [RedactionPipeline].
 */
@Singleton
class DiagnosticsRepositoryImpl
    @Inject
    constructor(
        private val timeline: DiagnosticTimeline,
        private val collector: DiagnosticCollector,
    ) : DiagnosticsRepository {
        // cancel-safe: cold SharedFlow collection; cancellation stops collection cleanly.
        override fun events(): Flow<DiagnosticEvent> = timeline.live

        // cancel-safe: DiagnosticCollector.record is non-suspending; this suspend wrapper
        // exists only to satisfy the DiagnosticsRepository interface contract.
        override suspend fun recordEvent(event: DiagnosticEvent) {
            collector.record(event)
        }
    }
