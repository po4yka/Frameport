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
