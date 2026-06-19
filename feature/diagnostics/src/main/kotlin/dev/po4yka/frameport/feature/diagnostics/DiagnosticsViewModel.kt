package dev.po4yka.frameport.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.DiagnosticEvent
import dev.po4yka.frameport.camera.diagnostics.DiagnosticBundleExporter
import dev.po4yka.frameport.camera.diagnostics.DiagnosticTimeline
import dev.po4yka.frameport.core.common.di.IoDispatcher
import dev.po4yka.frameport.core.model.FrameportError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * One-shot result of a diagnostic bundle export operation.
 *
 * Success carries only the file NAME (never the full path — privacy invariant).
 * Failure carries a typed [FrameportError] so the UI can render a meaningful message.
 */
sealed interface ExportResult {
    /** Export succeeded. [fileName] is the base name of the exported zip (e.g. "frameport-diagnostics-2026-06-14T00:00:00Z.zip"). */
    data class Success(
        val fileName: String,
    ) : ExportResult

    /** Export failed. [error] is a typed domain error; never a raw exception message containing device ids. */
    data class Failure(
        val error: FrameportError,
    ) : ExportResult
}

/**
 * ViewModel for the Diagnostics screen.
 *
 * Responsibilities:
 * - Exposes the live diagnostic event list from [DiagnosticTimeline.timeline] as a [StateFlow].
 * - Delegates bundle export to [DiagnosticBundleExporter] on [IoDispatcher].
 * - Emits one-shot [ExportResult] events for the UI to display as a snackbar.
 *
 * Composables MUST NOT inject this directly — use [hiltViewModel] from the Compose navigation layer.
 * ViewModels MUST NOT call JNI. No I/O on the main thread.
 */
@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        private val timeline: DiagnosticTimeline,
        private val exporter: DiagnosticBundleExporter,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        /**
         * Snapshot of all diagnostic events accumulated since process start.
         * Sourced from [DiagnosticTimeline.timeline] (StateFlow<List<DiagnosticEvent>>).
         * Emits immediately on first collection.
         */
        // cancel-safe: stateIn with WhileSubscribed; upstream StateFlow cancels cleanly on scope cancellation.
        val events: StateFlow<List<DiagnosticEvent>> =
            timeline.timeline.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

        /**
         * One-shot export result events. The UI collects this and shows a snackbar.
         * Replay is 0 — each event is consumed exactly once per active collector.
         *
         * extraBufferCapacity=64 ensures that rapid successive [exportBundle] calls
         * (e.g. double-tap) are buffered without suspending the emitting coroutine,
         * even if the collector is momentarily busy showing a previous snackbar.
         *
         * Note on event delivery: because replay=0 and there is no bridge coroutine,
         * events emitted before a collector subscribes are NOT replayed. The UI layer
         * uses repeatOnLifecycle(STARTED) which subscribes while the screen is visible;
         * events emitted while the screen is off (STOPPED) are intentionally dropped —
         * a stale "Export succeeded" snackbar on re-entry would be confusing.
         */
        // cancel-safe: MutableSharedFlow with no persistent coroutines; emit is fast-path
        // (buffer has capacity) and does not suspend in normal operation.
        private val _exportResult = MutableSharedFlow<ExportResult>(replay = 0, extraBufferCapacity = 64)
        val exportResult: SharedFlow<ExportResult> = _exportResult.asSharedFlow()

        /**
         * Triggers a diagnostic bundle export on [IoDispatcher].
         *
         * The result is emitted as a one-shot [ExportResult] via [exportResult].
         * The UI should show a snackbar with [ExportResult.Success.fileName] only —
         * never the full file path (privacy invariant).
         *
         * // NOT cancel-safe: if the coroutine is cancelled mid-export the zip may be partially written;
         * // the file is written to cacheDir and will be evicted by the OS on next launch if incomplete.
         */
        fun exportBundle() {
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        exporter.export(timeline)
                    }
                result.fold(
                    onSuccess = { file ->
                        _exportResult.emit(ExportResult.Success(fileName = file.name))
                    },
                    onFailure = { throwable ->
                        val error =
                            FrameportError.Unknown(
                                message = throwable.message ?: "Export failed",
                            )
                        _exportResult.emit(ExportResult.Failure(error = error))
                    },
                )
            }
        }
    }
