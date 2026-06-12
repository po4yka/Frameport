package dev.po4yka.frameport.camera.diagnostics

import dev.po4yka.frameport.camera.api.CameraDiagnosticEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

interface DiagnosticEventSink {
    fun events(): Flow<CameraDiagnosticEvent>

    suspend fun record(event: CameraDiagnosticEvent)
}

class NoOpDiagnosticEventSink : DiagnosticEventSink {
    private val events = MutableSharedFlow<CameraDiagnosticEvent>(extraBufferCapacity = 64)

    override fun events(): Flow<CameraDiagnosticEvent> = events

    override suspend fun record(event: CameraDiagnosticEvent) {
        events.emit(event)
    }
}
