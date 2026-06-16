package dev.po4yka.frameport.camera.diagnostics

import dev.po4yka.frameport.camera.api.DiagnosticEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
                extraBufferCapacity = 64,
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
         */
        fun append(event: DiagnosticEvent) {
            _timeline.update { current -> current + event }
            _live.tryEmit(event)
        }
    }
