package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.DiagnosticEvent
import dev.po4yka.frameport.camera.api.DiagnosticsRepository
import dev.po4yka.frameport.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [DiagnosticsRepository].
 *
 * Backed by an in-memory [MutableSharedFlow] with a replay buffer.
 * All events stay on-device; no upload path exists. See privacy-local-first.md.
 *
 * PRIVACY: callers MUST NOT include raw device serial, camera MAC, IP address,
 * or raw filenames in [DiagnosticEvent.message]. Use hashes or redacted strings.
 */
@Singleton
class DiagnosticsRepositoryImpl
    @Inject
    constructor(
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : DiagnosticsRepository {
        private val _events =
            MutableSharedFlow<DiagnosticEvent>(
                extraBufferCapacity = 256,
                replay = 64,
            )

        // cancel-safe: cold SharedFlow collection; cancellation stops collection cleanly.
        override fun events(): Flow<DiagnosticEvent> = _events.asSharedFlow()

        // cancel-safe: emit() suspends only when the 256-slot buffer is full; a cancelled
        // caller scope cancels that suspension cleanly with no partial state.
        override suspend fun recordEvent(event: DiagnosticEvent) {
            withContext(ioDispatcher) { _events.emit(event) }
        }
    }
