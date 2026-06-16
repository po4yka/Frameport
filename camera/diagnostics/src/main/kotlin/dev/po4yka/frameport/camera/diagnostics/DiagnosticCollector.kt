package dev.po4yka.frameport.camera.diagnostics

import dev.po4yka.frameport.camera.api.DiagnosticEvent
import dev.po4yka.frameport.camera.api.ErrorLayer
import dev.po4yka.frameport.camera.api.defaultCategory
import dev.po4yka.frameport.camera.api.diagnosticEvent
import dev.po4yka.frameport.core.model.FrameportError
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE single write path for diagnostics in Frameport.
 *
 * All event recording flows through this class. Callers MUST NOT construct
 * [DiagnosticEvent] and write to [DiagnosticTimeline] directly — use [record]
 * or [recordError] so that redaction, structuring, and Timber logging are
 * applied consistently.
 *
 * PRIVACY INVARIANTS enforced here:
 *  - [record]: [DiagnosticEvent.message], [DiagnosticEvent.metadata] values, and
 *    [DiagnosticEvent.sessionId] are redacted before storage or broadcast.
 *  - [recordError]: [FrameportError.message] and [context] values are redacted by
 *    the same [record] path before they enter [DiagnosticTimeline].
 *  - Timber calls use only the category name and a fixed description — no raw ids.
 *
 * Thread-safety: [DiagnosticTimeline.append] is thread-safe; [record] and [recordError]
 * may be called from any thread or coroutine context.
 */
@Singleton
class DiagnosticCollector
    @Inject
    constructor(
        private val timeline: DiagnosticTimeline,
        private val redactionPipeline: RedactionPipeline,
    ) {
        /**
         * Appends [event] to the timeline after redacting all caller-supplied text values.
         */
        fun record(event: DiagnosticEvent) {
            // cancel-safe: DiagnosticTimeline.append is non-suspending and thread-safe.
            val redactedEvent = event.redacted()
            timeline.append(redactedEvent)
            // Log at DEBUG — category name only; no raw identifiers.
            Timber.d("DiagnosticEvent recorded: layer=%s category=%s", redactedEvent.layer, redactedEvent.category)
        }

        /**
         * Builds and records a [DiagnosticEvent] from a typed [FrameportError].
         *
         * The event message is taken from [cause.message]. [record] redacts the final
         * event before storage, so callers do not need a separate redaction step.
         *
         * @param layer The architectural layer where the error originated.
         * @param cause Typed error; [FrameportError.message] becomes the event message.
         * @param context Key→value metadata. Values are redacted before storage.
         */
        fun recordError(
            layer: ErrorLayer,
            cause: FrameportError,
            context: Map<String, String> = emptyMap(),
        ) {
            // cancel-safe: record() is non-suspending.
            val event =
                diagnosticEvent(
                    layer = layer,
                    category = defaultCategory(layer),
                    message = cause.message,
                    metadata = context,
                )
            record(event)
            // Log at WARN — layer and category only; cause.message is already redacted per contract.
            Timber.w("DiagnosticError recorded: layer=%s", layer)
        }

        private fun DiagnosticEvent.redacted(): DiagnosticEvent =
            copy(
                message = redactionPipeline.redactDiagnosticText(message),
                metadata = metadata.mapValues { (_, value) -> redactionPipeline.redactDiagnosticText(value) },
                sessionId = redactionPipeline.redactDiagnosticText(sessionId),
            )
    }
