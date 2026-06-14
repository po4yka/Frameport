package dev.po4yka.frameport.camera.api

// ─── ShutterAction ────────────────────────────────────────────────────────────

/**
 * Shutter action for remote capture, shared by both the BLE path (maps to
 * [fuji_ble_protocol::ShootingRequest]) and the PTP-IP path (maps to the
 * InitiateCapture / TerminateOpenCapture opcode sequence).
 *
 * Values are intentionally named after the user-visible action, not after
 * wire codes. Wire encoding is the responsibility of the Rust layer.
 *
 * BLE wire mapping (master-constants.md §5, ble-wifi-discovery.md §ShootingRequest):
 *   HalfPress → S1 = 0x0001 LE
 *   FullPress → S2 = 0x0002 LE
 *   Release   → S0 = 0x0000 LE
 */
enum class ShutterAction {
    /** Half-press: triggers autofocus. BLE: S1 (0x0001). */
    HalfPress,

    /** Full-press: triggers capture. BLE: S2 (0x0002). */
    FullPress,

    /** Release / cancel: resets shutter state. BLE: S0 (0x0000). */
    Release,
}

// ─── RemoteCaptureRequest ─────────────────────────────────────────────────────

/**
 * Discriminated union of remote capture request types for [RemoteCaptureUseCase].
 *
 * The use case dispatches to the BLE path (writes [ShutterAction] to the
 * [CHR_SHOOTING_REQUEST] characteristic via [FujiBleClient]) or to the PTP-IP
 * path (calls [FujiNativeSdk.remoteShutter] which eventually drives the Rust
 * RemoteSession state machine).
 *
 * CHR_SHOOTING_REQUEST UUID: 7FCF49C6-4FF0-4777-A03D-1A79166AF7A8
 * Source: master-constants.md §5, ble-wifi-discovery.md §Camera Control. [H] LFJ, XPN, XBL
 */
sealed interface RemoteCaptureRequest {
    /** Send a shutter action over BLE to the camera's shooting-request characteristic. */
    data class BleShutter(
        val action: ShutterAction,
    ) : RemoteCaptureRequest

    /** Send a shutter action over PTP-IP (Wi-Fi). */
    data class PtpIpShutter(
        val action: ShutterAction,
        val sessionId: SessionId,
    ) : RemoteCaptureRequest
}

// ─── RemoteCaptureError ───────────────────────────────────────────────────────

/**
 * Typed errors for the remote capture flow.
 *
 * Never use raw [Throwable] across this boundary. Map all platform and native
 * errors to a variant here before they reach the ViewModel.
 */
sealed interface RemoteCaptureError {
    /** Camera model is not in the remote-capable allowlist (conservative capability gate). */
    data object IncompatibleCamera : RemoteCaptureError

    /** BLE characteristic write for the shutter request failed. [detail] is pre-redacted. */
    data class BleWriteFailed(
        val detail: String,
    ) : RemoteCaptureError

    /** PTP-IP native SDK call returned a failure. [detail] is pre-redacted. */
    data class PtpIpFailed(
        val detail: String,
    ) : RemoteCaptureError

    /** No active BLE connection when a BLE shutter action was requested. */
    data object NotConnectedBle : RemoteCaptureError

    /** No active Wi-Fi PTP-IP session when a PTP-IP shutter action was requested. */
    data object NotConnectedWifi : RemoteCaptureError
}

// ─── RemoteCaptureState ───────────────────────────────────────────────────────

/**
 * State machine for a single remote capture operation, emitted by
 * [RemoteCaptureUseCase] as a [kotlinx.coroutines.flow.Flow].
 *
 * Transitions (happy path):
 * ```
 * Idle → HalfPressed → FullPressed → CapturingInProgress → CaptureComplete → Idle
 *           ↓ (Release)
 *         Idle
 * ```
 * [Error] is a terminal state; the use case emits it and completes the flow.
 */
sealed interface RemoteCaptureState {
    /** No capture in progress; initial state. */
    data object Idle : RemoteCaptureState

    /** Autofocus half-press has been sent to the camera. */
    data object HalfPressed : RemoteCaptureState

    /** Full-press capture command has been sent; waiting for camera acknowledgement. */
    data object FullPressed : RemoteCaptureState

    /** Camera is actively capturing (PTP-IP CaptureComplete event not yet received). */
    data object CapturingInProgress : RemoteCaptureState

    /**
     * Capture acknowledged by the camera.
     *
     * For the PTP-IP path: the EventChannelReader received a CaptureComplete (0x400D) event.
     * For the BLE path: the characteristic write returned success.
     * [objectHandle] is the PTP object handle of the captured image, when available (PTP-IP path only).
     */
    data class CaptureComplete(
        val objectHandle: Long? = null,
    ) : RemoteCaptureState

    /** Terminal error state. */
    data class Error(
        val error: RemoteCaptureError,
    ) : RemoteCaptureState
}
