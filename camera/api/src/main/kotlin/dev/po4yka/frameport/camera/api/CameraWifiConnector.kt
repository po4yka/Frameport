package dev.po4yka.frameport.camera.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for Android-owned camera Wi-Fi network selection and socket creation.
 *
 * Owns: WifiNetworkSpecifier construction, NetworkCallback lifecycle, socket creation,
 * network binding, socket connection, and file descriptor handoff.
 *
 * Does NOT own: PTP-IP framing, session state, media enumeration, or any protocol logic.
 * Those belong to the Rust layer (fuji-rs) after receiving an [OwnedSocketHandle].
 *
 * See: docs/adr/0002-wifi-socket-fd-handoff.md, docs/android/wifi-network-routing.md
 */
interface CameraWifiConnector {
    val state: StateFlow<CameraWifiState>

    // cancel-safe: CompletableDeferred resolves once; no shared mutable state mutated after resolve.
    suspend fun requestCameraNetwork(credentials: CameraWifiCredentials): Result<CameraNetworkHandle>

    // cancel-safe: socket creation and connect are synchronous within withContext; cancellation closes the scope cleanly.
    suspend fun openBoundSocket(
        handle: CameraNetworkHandle,
        endpoint: CameraEndpoint,
    ): Result<OwnedSocketHandle>

    // cancel-safe: idempotent cleanup; second call is a no-op; no suspension points after state transition.
    suspend fun release(handle: CameraNetworkHandle)

    /**
     * Open, bind, and connect a TCP socket to the PTP-IP live-view channel endpoint
     * (port [LIVEVIEW_CHANNEL_PORT] = 55742, master-constants.md §1 — `D9BE`),
     * then hand off the duplicated fd to the caller for passing to the Rust layer.
     *
     * The live-view channel is opened LAZILY — only after the camera has been placed
     * in live-view mode via SetFunctionMode (FunctionMode = 22 = IMAGE_LIVE_VIEW).
     *
     * fd ownership contract (identical to [openBoundSocket] / [openEventSocket]):
     * The returned [OwnedSocketHandle.fd] is a dup of the underlying socket fd.
     * Ownership transfers to the CALLER (Rust layer). Android has already closed its
     * own socket reference separately. Rust closes its dup when done.
     * See docs/rust/fd-ownership.md and ADR-0002.
     *
     * TCP socket config: TCP_NODELAY = true, SO_KEEPALIVE = true, 1 s connect timeout.
     * Source: master-constants.md §1. [H] FCW, LFJ, FJH, XPN, XBL
     *
     * State transitions emitted during this call:
     *   [CameraWifiState.LiveViewSocketRequested] → [CameraWifiState.LiveViewSocketBound]
     *   → [CameraWifiState.LiveViewSocketHandedOff] on success
     *   → [CameraWifiState.Error] on failure
     *
     * @param handle An active [CameraNetworkHandle] (same handle used for the command channel).
     * @return [Result.success] with the owned, dup'd fd; [Result.failure] with a typed [CameraWifiError].
     */
    // cancel-safe: socket creation and connect are synchronous within withContext; cancellation closes the scope cleanly.
    suspend fun openLiveViewSocket(handle: CameraNetworkHandle): Result<OwnedSocketHandle>

    /**
     * Open, bind, and connect a second TCP socket to the PTP-IP event channel endpoint,
     * then hand off the duplicated fd to the caller for passing to the Rust layer.
     *
     * The event channel (port [EVENT_CHANNEL_PORT] = 55741, master-constants.md §1) is
     * opened LAZILY — only after InitiateOpenCapture (0x101C) triggers the camera to
     * start listening on that port.
     *
     * fd ownership contract (identical to [openBoundSocket] / M07 command-channel pattern):
     * The returned [OwnedSocketHandle.fd] is a dup of the underlying socket fd.
     * Ownership transfers to the CALLER (Rust layer). The Android side has already
     * closed its own socket reference separately. Rust closes its dup when done.
     * See docs/rust/fd-ownership.md and ADR-0002.
     *
     * TCP socket config: TCP_NODELAY = true, SO_KEEPALIVE = true, 1 s connect timeout.
     * Source: master-constants.md §1. [H] FCW, LFJ, FJH, XPN, XBL
     *
     * State transitions emitted during this call:
     *   [CameraWifiState.EventSocketRequested] → [CameraWifiState.EventSocketBound]
     *   → [CameraWifiState.EventSocketHandedOff] on success
     *   → [CameraWifiState.Error] on failure
     *
     * @param handle An active [CameraNetworkHandle] (same handle used for the command channel).
     * @return [Result.success] with the owned, dup'd fd; [Result.failure] with a typed [CameraWifiError].
     */
    // cancel-safe: socket creation and connect are synchronous within withContext; cancellation closes the scope cleanly.
    suspend fun openEventSocket(handle: CameraNetworkHandle): Result<OwnedSocketHandle>
}

/**
 * Typed state machine for [CameraWifiConnector].
 *
 * Happy-path emission order:
 * Idle → RequestingNetwork → NetworkAvailable → BindingSocket → ConnectingSocket → HandingOffSocketFd → Connected
 *
 * Terminal states: [Connected], [Closed], [Error].
 * [Error] replaces the previous flat [CameraWifiState] enum — never surface raw Throwable at the boundary.
 */
sealed class CameraWifiState {
    data object Idle : CameraWifiState()

    data object RequestingNetwork : CameraWifiState()

    data object NetworkAvailable : CameraWifiState()

    data object BindingSocket : CameraWifiState()

    data object ConnectingSocket : CameraWifiState()

    data object HandingOffSocketFd : CameraWifiState()

    data object Connected : CameraWifiState()

    data object Releasing : CameraWifiState()

    data object Closed : CameraWifiState()

    data class Error(
        val cause: CameraWifiError,
    ) : CameraWifiState()

    // ── Event-channel socket states (M15 remote capture) ─────────────────────
    // Emitted by [CameraWifiConnector.openEventSocket] during the lazy event-channel
    // handoff. These run AFTER [Connected] and do not replace the command-channel state.

    /**
     * Android is creating and binding a socket for the PTP-IP event channel
     * (port EVENT_CHANNEL_PORT = 55741, master-constants.md §1).
     * Only emitted after InitiateOpenCapture triggers the camera to open that port.
     */
    data object EventSocketRequested : CameraWifiState()

    /**
     * Event-channel socket is bound to the camera network and connected to the
     * camera endpoint; fd is about to be dup'd for handoff.
     */
    data object EventSocketBound : CameraWifiState()

    /**
     * Dup'd event-channel fd has been handed to the caller ([OwnedSocketHandle]).
     * Ownership has transferred; the Rust EventChannelReader now owns the fd.
     */
    data object EventSocketHandedOff : CameraWifiState()

    /**
     * Event-channel socket was released (session ended or error recovery).
     * Transitions back to [Connected] if the command channel is still active.
     */
    data object EventSocketReleased : CameraWifiState()

    // ── Live-view channel socket states (M16) ────────────────────────────────
    // Emitted by [CameraWifiConnector.openLiveViewSocket] during the lazy
    // live-view channel handoff. Port 55742 (0xD9BE, master-constants.md §1).
    // These run AFTER [Connected] and do not replace the command-channel state.

    /**
     * Android is creating and binding a socket for the PTP-IP live-view channel
     * (port LIVEVIEW_CHANNEL_PORT = 55742, master-constants.md §1 — code D9BE).
     * Only emitted after the camera has been placed in IMAGE_LIVE_VIEW mode
     * (FunctionMode = 22).
     * Source: transfer-liveview.md §6g, wifi-ptp-ip.md §LiveViewLater. [H] FCW, LFJ, FJH, XPN, XBL
     */
    data object LiveViewSocketRequested : CameraWifiState()

    /**
     * Live-view socket is bound to the camera network and connected to port 55742;
     * fd is about to be dup'd for handoff to the Rust live-view read loop.
     */
    data object LiveViewSocketBound : CameraWifiState()

    /**
     * Dup'd live-view fd has been handed to the caller ([OwnedSocketHandle]).
     * Ownership has transferred; the Rust LiveViewParser read loop now owns the fd.
     */
    data object LiveViewSocketHandedOff : CameraWifiState()

    /**
     * Live-view socket was released (session ended or error recovery).
     * Transitions back to [Connected] if the command channel is still active.
     */
    data object LiveViewSocketReleased : CameraWifiState()
}

/**
 * Typed errors surfaced by [CameraWifiConnector]. Never use raw [Throwable] at the interface boundary.
 *
 * See: docs/adr/0002-wifi-socket-fd-handoff.md §Failure Modes
 */
sealed class CameraWifiError {
    /** Android permission missing: CHANGE_WIFI_STATE or NEARBY_WIFI_DEVICES (API 33+). */
    data object PermissionDenied : CameraWifiError()

    /** User dismissed the system Wi-Fi network request dialog. */
    data object UserRejectedNetworkRequest : CameraWifiError()

    /** Camera Wi-Fi network not available; onUnavailable callback fired. */
    data object NetworkUnavailable : CameraWifiError()

    /** Network binding call failed. [detail] is a safe, non-PII diagnostic string. */
    data class SocketBindFailed(
        val detail: String,
    ) : CameraWifiError()

    /** TCP connect to the camera endpoint failed or timed out. [detail] is a safe diagnostic string. */
    data class SocketConnectFailed(
        val detail: String,
    ) : CameraWifiError()

    /** Camera Wi-Fi network was lost while a session was active. */
    data object NetworkLost : CameraWifiError()

    /** Unexpected error not covered by the above. [cause] is retained for diagnostics but must NOT be logged raw. */
    data class UnexpectedError(
        val cause: Throwable,
    ) : CameraWifiError()
}

/**
 * Credentials needed to connect to the camera Wi-Fi access point.
 * PRIVACY: passphrase must never be logged. See privacy-local-first.md.
 */
data class CameraWifiCredentials(
    val ssid: String,
    val passphrase: String?,
)

/**
 * Camera TCP endpoint address.
 *
 * Defaults: host = [CAMERA_AP_IP] (192.168.0.1 [H] FCW, XPN, XBL — master-constants §1),
 *            port = [COMMAND_PORT] (55740 [H] FCW, LFJ, FJH, XPN, XBL — master-constants §1).
 */
data class CameraEndpoint(
    val host: String,
    val port: Int,
)

/**
 * Opaque handle to an Android [android.net.Network] for the camera Wi-Fi.
 *
 * Passed back to [CameraWifiConnector.openBoundSocket] and [CameraWifiConnector.release].
 * The value is an internal correlation key; do not interpret it.
 */
@JvmInline
value class CameraNetworkHandle(
    val value: String,
)

/**
 * An owned, duplicated raw file descriptor suitable for passing to the Rust layer.
 *
 * Ownership semantics (CRITICAL — see docs/rust/fd-ownership.md and ADR-0002):
 * Ownership of this duplicated file descriptor transfers to the CALLER. Rust
 * takes ownership when passed to NativeFujiSdk.openWifiSession(...) and is responsible for
 * closing it via the JNI bridge. The Android side has already closed/retained its OWN socket
 * separately; this fd is an independent dup. Double-close is prevented because Android never
 * closes this duplicate.
 *
 * The [fd] value is the raw int fd. A value of -1 indicates an invalid/unset state (e.g. in tests).
 */
@JvmInline
value class OwnedSocketHandle(
    val fd: Int,
)
