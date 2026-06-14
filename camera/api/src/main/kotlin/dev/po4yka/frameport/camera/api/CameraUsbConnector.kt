package dev.po4yka.frameport.camera.api

import kotlinx.coroutines.flow.StateFlow

// ─── UsbTransportHandle ──────────────────────────────────────────────────────

/**
 * Opaque handle returned after the Android-side UsbManager has opened the USB
 * device and the dup'd file descriptor has been registered with the Rust layer.
 *
 * fd ownership contract (CRITICAL — see docs/rust/fd-ownership.md and ADR-0002):
 * Android calls [UsbDeviceConnection.fileDescriptor], then [Os.dup()] to produce
 * an independent copy. The [sessionId] is registered by Rust after receiving the
 * dup'd fd via [FujiNativeSdk.openUsbSession]. Rust owns and closes the dup;
 * Android closes its original [UsbDeviceConnection] via normal [UsbDeviceConnection.close].
 * Never close both from the same side — see "// OWNERSHIP:" comments at the dup site.
 *
 * [sessionId] is the Rust-assigned session id (positive Long) returned by
 * [FujiNativeSdk.openUsbSession]. A value of -1 indicates an invalid/unset handle.
 */
@JvmInline
value class UsbTransportHandle(
    val sessionId: SessionId,
)

// ─── UsbSessionState ─────────────────────────────────────────────────────────

/**
 * State machine for the USB PTP session lifecycle, emitted by
 * [CameraUsbConnector.state] as a [StateFlow].
 *
 * Happy-path transitions:
 * ```
 * Disconnected
 *   → DeviceDetected
 *   → PermissionPending
 *   → OpeningDevice
 *   → DeviceOpen
 *   → FdHandoff
 *   → RustTransportOpen
 *   → PtpSessionOpening
 *   → UsbSessionReady
 *   → Closing
 *   → Closed
 * ```
 *
 * Terminal error state: [Error].
 * [PermissionDenied] is a recoverable terminal (user can retry).
 *
 * NOTE: [Error.message] must be a pre-redacted, PII-free string per
 * privacy-local-first.md. Never include USB serial numbers, device descriptors,
 * or hardware identifiers in the message.
 */
sealed interface UsbSessionState {
    /** Initial state; no USB PTP camera detected. */
    data object Disconnected : UsbSessionState

    /**
     * A USB device matching the PTP camera criteria was detected via
     * ACTION_USB_DEVICE_ATTACHED. [deviceRef] is an opaque identifier for
     * the device — do NOT log raw USB serial numbers.
     */
    data class DeviceDetected(
        val deviceRef: UsbDeviceRef,
    ) : UsbSessionState

    /**
     * Android has sent a permission request via [UsbManager.requestPermission].
     * Waiting for the system dialog result.
     */
    data object PermissionPending : UsbSessionState

    /**
     * The user denied the UsbManager permission dialog.
     * Recoverable: the user can grant permission by reconnecting the device.
     */
    data object PermissionDenied : UsbSessionState

    /** Permission was granted; [UsbManager.openDevice] is in progress. */
    data object OpeningDevice : UsbSessionState

    /**
     * [UsbManager.openDevice] succeeded. [UsbDeviceConnection] is open.
     * The raw [fileDescriptor] is available but has not yet been dup'd.
     */
    data object DeviceOpen : UsbSessionState

    /**
     * Android has called [Os.dup] on the connection's [fileDescriptor] and is
     * about to pass the dup'd fd and USB descriptor bytes to Rust via
     * [FujiNativeSdk.openUsbSession].
     *
     * // OWNERSHIP: Android keeps + closes the original via UsbDeviceConnection.close().
     * //            Rust owns + closes the dup returned by Os.dup().
     */
    data object FdHandoff : UsbSessionState

    /**
     * The dup'd fd and descriptor bytes were accepted by Rust
     * ([FujiNativeSdk.openUsbSession] returned [Result.success]).
     * The Rust BulkTransport is open and holds the fd.
     */
    data object RustTransportOpen : UsbSessionState

    /**
     * The Rust [UsbPtpSession] is sending the PTP OpenSession command
     * over the bulk endpoints.
     */
    data object PtpSessionOpening : UsbSessionState

    /**
     * A full USB PTP session is established. [handle] wraps the Rust-assigned
     * session id and can be passed to [ImportObjectUseCase] or other use cases.
     */
    data class UsbSessionReady(
        val handle: UsbTransportHandle,
    ) : UsbSessionState

    /** Session teardown in progress. */
    data object Closing : UsbSessionState

    /** Session fully closed; fd dup released by Rust; UsbDeviceConnection closed by Android. */
    data object Closed : UsbSessionState

    /**
     * An unrecoverable error occurred.
     *
     * @param error Typed [UsbSessionError] describing the failure.
     * @param message Pre-redacted, PII-free diagnostic description.
     */
    data class Error(
        val error: UsbSessionError,
        val message: String,
    ) : UsbSessionState
}

// ─── UsbDeviceRef ─────────────────────────────────────────────────────────────

/**
 * Opaque, safe reference to a detected USB camera device.
 *
 * PRIVACY: [displayName] is for UI only and must not include raw USB serial
 * numbers. [deviceKey] is an internal correlation key used by [CameraUsbConnector]
 * to map the Android [UsbDevice]; it is not exposed beyond the module boundary.
 */
data class UsbDeviceRef(
    /** Internal correlation key — not shown to the user and not logged. */
    val deviceKey: String,
    /** Human-readable label suitable for display; must NOT contain serial numbers. */
    val displayName: String,
)

// ─── UsbSessionError ─────────────────────────────────────────────────────────

/**
 * Typed errors for the USB PTP session lifecycle.
 *
 * Never surface raw [Throwable] or Android USB errno values across this boundary.
 * Map all platform errors to a variant here before they reach the ViewModel.
 */
sealed interface UsbSessionError {
    /** Android denied the UsbManager permission request. */
    data object PermissionDenied : UsbSessionError

    /** The USB device was detached before the UsbManager permission dialog resolved. */
    data object DeviceDetachedBeforePermission : UsbSessionError

    /** [UsbManager.openDevice] returned null or the connection could not be established. */
    data object OpenDeviceFailed : UsbSessionError

    /** [Os.dup] on the connection file descriptor failed. [detail] is safe, non-PII. */
    data class FdDupFailed(
        val detail: String,
    ) : UsbSessionError

    /** Rust rejected the dup'd fd or descriptor bytes (FujiNativeSdk.openUsbSession failed). */
    data class RustSessionOpenFailed(
        val detail: String,
    ) : UsbSessionError

    /** The PTP OpenSession command failed over USB bulk endpoints. */
    data object PtpOpenSessionFailed : UsbSessionError

    /** The USB device was detached while a session was active. */
    data object DeviceDetachedDuringSession : UsbSessionError

    /** An unexpected error not covered above. [cause] retained for diagnostics; must NOT be logged raw. */
    data class UnexpectedError(
        val cause: Throwable,
    ) : UsbSessionError
}

// ─── CameraUsbConnector ───────────────────────────────────────────────────────

/**
 * Interface for Android-owned USB camera device lifecycle.
 *
 * Owns: [UsbManager] permission request, device open, file descriptor dup and
 * handoff, and session teardown. Also owns BroadcastReceiver registrations for
 * ACTION_USB_DEVICE_ATTACHED and ACTION_USB_DEVICE_DETACHED.
 *
 * Does NOT own: PTP packet codec, bulk transfer state, or any protocol logic.
 * Those belong to the Rust layer (fuji-rs / fuji-usb-ptp) after receiving the
 * dup'd fd via [FujiNativeSdk.openUsbSession].
 *
 * Boundary invariants (CLAUDE.md):
 * - NO root, NO /dev/bus/usb path, NO shell commands.
 * - NO NEARBY_WIFI_DEVICES permission, NO location permission.
 * - USB operations happen only during an active, user-initiated session.
 *
 * See: docs/android/bluetooth-architecture.md (pattern reference),
 *      docs/rust/fd-ownership.md, ADR-0002.
 */
interface CameraUsbConnector {
    /** Current USB session state; transitions are described on [UsbSessionState]. */
    val state: StateFlow<UsbSessionState>

    /**
     * Request UsbManager permission for [device].
     *
     * Emits [UsbSessionState.PermissionPending] then either
     * [UsbSessionState.OpeningDevice] (granted) or [UsbSessionState.PermissionDenied].
     *
     * cancel-safe: permission result is delivered via BroadcastReceiver;
     * suspension ends when the result arrives or the coroutine is cancelled.
     */
    suspend fun requestPermission(device: UsbDeviceRef): Result<Unit>

    /**
     * Open the USB device connection, dup the fd, and hand it off to Rust.
     *
     * Transitions: [UsbSessionState.OpeningDevice] → [UsbSessionState.DeviceOpen]
     * → [UsbSessionState.FdHandoff] → [UsbSessionState.RustTransportOpen]
     * → [UsbSessionState.PtpSessionOpening] → [UsbSessionState.UsbSessionReady]
     * on success, or [UsbSessionState.Error] on any failure.
     *
     * fd ownership contract:
     * Android calls [Os.dup] on [UsbDeviceConnection.fileDescriptor] and passes
     * the dup to [FujiNativeSdk.openUsbSession]. Rust owns and closes the dup.
     * Android closes the original [UsbDeviceConnection] via [close].
     * See docs/rust/fd-ownership.md and the // OWNERSHIP: comment at the dup site.
     *
     * @param device The device ref from [UsbSessionState.DeviceDetected].
     * @param descriptors Raw USB interface descriptor bytes for endpoint discovery in Rust.
     * @return [Result.success] with a [UsbTransportHandle] wrapping the Rust session id;
     *         [Result.failure] with a typed [UsbSessionError].
     *
     * cancel-safe: each step (openDevice, dup, openUsbSession) runs inside
     * withContext(IO); cancellation propagates to that scope.
     */
    suspend fun openSession(
        device: UsbDeviceRef,
        descriptors: ByteArray,
    ): Result<UsbTransportHandle>

    /**
     * Close the USB session and release all resources.
     *
     * Transitions: [UsbSessionState.Closing] → [UsbSessionState.Closed].
     * Idempotent: calling close on an already-closed session is a no-op.
     *
     * cancel-safe: cleanup calls are best-effort; no suspension points after
     * state is set to Closing.
     */
    suspend fun close(handle: UsbTransportHandle)
}
