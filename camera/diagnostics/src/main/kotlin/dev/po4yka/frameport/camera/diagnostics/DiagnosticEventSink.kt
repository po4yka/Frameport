package dev.po4yka.frameport.camera.diagnostics

import dev.po4yka.frameport.camera.api.DiagnosticEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Low-level sink for camera diagnostic events used by :camera:diagnostics module.
 *
 * The domain-layer [dev.po4yka.frameport.camera.api.DiagnosticsRepository] is the
 * primary interface for feature modules. This sink is retained for the :camera:diagnostics
 * subsystem's internal use (e.g. connection-layer event recording).
 *
 * PRIVACY: callers MUST NOT include raw device serial, camera MAC, IP address, or raw
 * filenames in [DiagnosticEvent.message]. Use hashes or redacted strings.
 */
interface DiagnosticEventSink {
    // cancel-safe: cold SharedFlow collection; cancellation stops collection cleanly.
    fun events(): Flow<DiagnosticEvent>

    // cancel-safe: emit into a buffered SharedFlow; never blocks.
    suspend fun record(event: DiagnosticEvent)
}

class NoOpDiagnosticEventSink : DiagnosticEventSink {
    private val events = MutableSharedFlow<DiagnosticEvent>(extraBufferCapacity = 64)

    override fun events(): Flow<DiagnosticEvent> = events

    override suspend fun record(event: DiagnosticEvent) {
        events.emit(event)
    }
}
