package dev.po4yka.frameport.camera.diagnostics

import dev.po4yka.frameport.camera.api.DiagnosticEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Append-only in-memory store for [DiagnosticEvent] instances.
 *
 * There are two observation surfaces:
 *  - [live]: a [SharedFlow] (replay=0) that broadcasts each event as it arrives.
 *    Collectors receive only events emitted while they are actively collecting.
 *  - [timeline]: a [StateFlow] accumulating the full ordered list since process start.
 *    Reading [timeline].value at any time gives the complete snapshot — used by
 *    [DiagnosticBundleExporter] and the diagnostics ViewModel.
 *
 * Thread-safety: [MutableSharedFlow.tryEmit] and [MutableStateFlow.update] are both
 * thread-safe. [append] may be called from any thread without additional synchronisation.
 *
 * PRIVACY: this class stores whatever [DiagnosticEvent] instances it receives; it does
 * NOT perform redaction. [DiagnosticCollector] is the single production write path and
 * redacts events before [append] is called.
 */
@Singleton
class DiagnosticTimeline
    @Inject
    constructor() {
        private val _live =
            MutableSharedFlow<DiagnosticEvent>(
                replay = 0,
                // M-11: raised from 64 to 256 to reduce the probability of drops during
                // burst-emission scenarios (e.g. rapid transfer progress events). tryEmit
                // below logs a warning when the buffer is still exhausted.
                extraBufferCapacity = 256,
            )

        /** Hot, replay=0 broadcast of each newly appended event. */
        val live: SharedFlow<DiagnosticEvent> = _live.asSharedFlow()

        private val _timeline = MutableStateFlow<List<DiagnosticEvent>>(emptyList())

        /** Accumulated snapshot of all events since process start. Thread-safe read via [StateFlow.value]. */
        val timeline: StateFlow<List<DiagnosticEvent>> = _timeline.asStateFlow()

        /**
         * Appends [event] to the timeline and broadcasts it on [live].
         *
         * [tryEmit] is used for [_live]: if no collector is present or the buffer is full
         * the event is dropped from the hot flow but still persisted in [_timeline].
         * This keeps [append] non-suspending and safe to call from any coroutine context.
         *
         * M-11: a dropped event is logged at WARN level (category only — no raw identifiers)
         * so that buffer-exhaustion is observable in diagnostics without silently losing events.
         */
        fun append(event: DiagnosticEvent) {
            _timeline.update { current -> current + event }
            if (!_live.tryEmit(event)) {
                // Event persisted in _timeline but dropped from the live hot flow.
                // No raw identifiers logged — category name only.
                Timber.w(
                    "DiagnosticTimeline: live-flow buffer full, event dropped from broadcast (category=%s)",
                    event.category::class.simpleName,
                )
            }
        }
    }
