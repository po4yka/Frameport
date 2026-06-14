package dev.po4yka.frameport.camera.api

import java.time.Instant

// ─── ErrorLayer ───────────────────────────────────────────────────────────────

/**
 * The architectural layer at which a diagnostic event originated.
 * Exactly 14 values, matching docs/protocol/error-model.md line 118.
 */
enum class ErrorLayer {
    UserAction,
    Permission,
    Bluetooth,
    Wifi,
    Socket,
    Usb,
    NativeBoundary,
    Transport,
    Protocol,
    CameraState,
    MediaTransfer,
    Storage,
    Location,
    Diagnostics,
}

// ─── DiagnosticCategory ───────────────────────────────────────────────────────

/**
 * Sealed hierarchy of diagnostic categories, one concrete subtype per [ErrorLayer].
 * Each subtype overrides [layer] to declare its owning layer.
 *
 * Use [defaultCategory] to obtain the canonical category for a given [ErrorLayer]
 * without explicitly naming the subtype.
 */
sealed interface DiagnosticCategory {
    val layer: ErrorLayer

    // ── UserAction ────────────────────────────────────────────────────────────
    data object UserInitiated : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.UserAction
    }

    // ── Permission ────────────────────────────────────────────────────────────
    data object PermissionDenied : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.Permission
    }

    // ── Bluetooth ─────────────────────────────────────────────────────────────
    data object BluetoothEvent : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.Bluetooth
    }

    // ── Wifi ──────────────────────────────────────────────────────────────────
    data object WifiEvent : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.Wifi
    }

    // ── Socket ────────────────────────────────────────────────────────────────
    data object SocketEvent : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.Socket
    }

    // ── Usb ───────────────────────────────────────────────────────────────────
    data object UsbEvent : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.Usb
    }

    // ── NativeBoundary ────────────────────────────────────────────────────────
    data object NativeBoundaryEvent : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.NativeBoundary
    }

    // ── Transport ─────────────────────────────────────────────────────────────
    data object TransportEvent : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.Transport
    }

    // ── Protocol ──────────────────────────────────────────────────────────────
    data object ProtocolEvent : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.Protocol
    }

    // ── CameraState ───────────────────────────────────────────────────────────
    data object CameraStateEvent : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.CameraState
    }

    // ── MediaTransfer ─────────────────────────────────────────────────────────
    data object MediaTransferEvent : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.MediaTransfer
    }

    // ── Storage ───────────────────────────────────────────────────────────────
    data object StorageEvent : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.Storage
    }

    // ── Location ──────────────────────────────────────────────────────────────
    data object LocationEvent : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.Location
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────
    data object DiagnosticsEvent : DiagnosticCategory {
        override val layer: ErrorLayer get() = ErrorLayer.Diagnostics
    }
}

/**
 * Returns the canonical [DiagnosticCategory] for the given [ErrorLayer].
 *
 * The `when` expression is exhaustive over all 14 [ErrorLayer] variants,
 * which is verified at compile time (no `else` branch).
 */
fun defaultCategory(layer: ErrorLayer): DiagnosticCategory =
    when (layer) {
        ErrorLayer.UserAction -> DiagnosticCategory.UserInitiated
        ErrorLayer.Permission -> DiagnosticCategory.PermissionDenied
        ErrorLayer.Bluetooth -> DiagnosticCategory.BluetoothEvent
        ErrorLayer.Wifi -> DiagnosticCategory.WifiEvent
        ErrorLayer.Socket -> DiagnosticCategory.SocketEvent
        ErrorLayer.Usb -> DiagnosticCategory.UsbEvent
        ErrorLayer.NativeBoundary -> DiagnosticCategory.NativeBoundaryEvent
        ErrorLayer.Transport -> DiagnosticCategory.TransportEvent
        ErrorLayer.Protocol -> DiagnosticCategory.ProtocolEvent
        ErrorLayer.CameraState -> DiagnosticCategory.CameraStateEvent
        ErrorLayer.MediaTransfer -> DiagnosticCategory.MediaTransferEvent
        ErrorLayer.Storage -> DiagnosticCategory.StorageEvent
        ErrorLayer.Location -> DiagnosticCategory.LocationEvent
        ErrorLayer.Diagnostics -> DiagnosticCategory.DiagnosticsEvent
    }

// ─── DiagnosticEvent ──────────────────────────────────────────────────────────

/**
 * A single structured diagnostic event in the Frameport diagnostics pipeline.
 *
 * PRIVACY INVARIANTS (enforced by callers and [DiagnosticCollector]):
 *  - [message] must already be redacted before construction. No raw device
 *    serial numbers, MAC addresses, SSIDs, passphrases, IPs, GPS coordinates,
 *    or raw filenames may appear in this field.
 *  - [metadata] values must be pre-redacted by the caller. Keys may be plain text.
 *  - [sessionId] is an opaque handle, never a raw device identifier.
 *
 * Use the [diagnosticEvent] factory to stamp [timestamp] automatically.
 */
data class DiagnosticEvent(
    val timestamp: Instant,
    val layer: ErrorLayer,
    val category: DiagnosticCategory,
    /** Already-redacted human-readable description. MUST NOT contain raw device identifiers. */
    val message: String,
    /** Pre-redacted key→value pairs for structured context. Values must be sanitised by caller. */
    val metadata: Map<String, String> = emptyMap(),
    /** Opaque session handle. MUST NOT be a raw device id (serial, MAC, IP). */
    val sessionId: String = "",
)

/**
 * Factory function for [DiagnosticEvent] that stamps [timestamp] automatically.
 *
 * Prefer this factory over direct construction so that [timestamp] is never
 * hand-stamped by callers, eliminating clock-skew bugs.
 *
 * @param layer The architectural layer that produced this event.
 * @param category The specific [DiagnosticCategory] within that layer.
 * @param message Already-redacted description. No raw device identifiers.
 * @param metadata Pre-redacted structured context. Values must be sanitised by the caller.
 * @param sessionId Opaque session handle. Must not be a raw device identifier.
 * @param at Timestamp; defaults to [Instant.now]. Override in tests only.
 */
fun diagnosticEvent(
    layer: ErrorLayer,
    category: DiagnosticCategory,
    message: String,
    metadata: Map<String, String> = emptyMap(),
    sessionId: String = "",
    at: Instant = Instant.now(),
): DiagnosticEvent =
    DiagnosticEvent(
        timestamp = at,
        layer = layer,
        category = category,
        message = message,
        metadata = metadata,
        sessionId = sessionId,
    )
