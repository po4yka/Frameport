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
 *  - [record]: the caller is responsible for pre-redacting [DiagnosticEvent.message]
 *    and all [DiagnosticEvent.metadata] values before calling [record]. This class
 *    does NOT re-redact an already-built event; it forwards it to [DiagnosticTimeline].
 *  - [recordError]: the message is sourced exclusively from [FrameportError.message],
 *    which must not contain raw device identifiers. Context values passed via [context]
 *    are treated as caller-redacted. Raw ids MUST be hashed via [RedactionPipeline]
 *    before being placed in [context].
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
        @Suppress("UnusedPrivateMember")
        private val redactionPipeline: RedactionPipeline,
    ) {
        /**
         * Appends a pre-built [event] to the timeline.
         *
         * The caller is responsible for ensuring [event.message] and all [event.metadata]
         * values are already redacted. This method does not inspect or modify the event.
         */
        fun record(event: DiagnosticEvent) {
            // cancel-safe: DiagnosticTimeline.append is non-suspending and thread-safe.
            timeline.append(event)
            // Log at DEBUG — category name only; no raw identifiers.
            Timber.d("DiagnosticEvent recorded: layer=%s category=%s", event.layer, event.category)
        }

        /**
         * Builds and records a [DiagnosticEvent] from a typed [FrameportError].
         *
         * The event message is taken from [cause.message], which must not contain raw
         * device identifiers. Values in [context] must be caller-redacted (e.g. via
         * [RedactionPipeline]) before being passed here.
         *
         * @param layer The architectural layer where the error originated.
         * @param cause Typed error; [FrameportError.message] becomes the event message.
         * @param context Pre-redacted key→value metadata. MUST NOT contain raw serial /
         *   MAC / SSID / IP / GPS / filename values.
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
    }
