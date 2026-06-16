package dev.po4yka.frameport.camera.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.po4yka.frameport.camera.api.CameraEndpoint
import dev.po4yka.frameport.camera.api.CameraNetworkHandle
import dev.po4yka.frameport.camera.api.CameraWifiConnector
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.CameraWifiError
import dev.po4yka.frameport.camera.api.CameraWifiState
import dev.po4yka.frameport.camera.api.OwnedSocketHandle
import dev.po4yka.frameport.core.common.di.IoDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [CameraWifiConnector].
 *
 * Owns WifiNetworkSpecifier construction, NetworkCallback lifecycle, socket creation,
 * network binding, TCP connect, and fd duplication/handoff to Rust.
 *
 * Constants from master-constants §1 [H] FCW, XPN, XBL, LFJ, FJH:
 *   CAMERA_AP_IP   = 192.168.0.1   — camera always acts as Wi-Fi AP
 *   COMMAND_PORT   = 55740         — command/control channel (0xD9BC)
 *   CONNECT_TIMEOUT_MS = 1000      — non-blocking connect with 1-second timeout
 *   TCP_NODELAY = true             — disable Nagle (required)
 *   SO_KEEPALIVE = true            — keepalive (required)
 *
 * See: docs/adr/0002-wifi-socket-fd-handoff.md, docs/android/wifi-network-routing.md
 * Privacy: docs/security — do NOT log ssid/bssid/passphrase/MAC. Log only state transitions and error categories.
 */
@Singleton
class CameraWifiConnectorImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : CameraWifiConnector {
        companion object {
            /** Camera AP IPv4 address. [H] FCW, XPN, XBL — master-constants §1 */
            const val CAMERA_AP_IP = "192.168.0.1"

            /** Command/control channel TCP port (0xD9BC). [H] FCW, LFJ, FJH, XPN, XBL — master-constants §1 */
            const val COMMAND_PORT = 55740

            /** Event channel TCP port (0xD9BD). [H] FCW, LFJ, FJH, XPN, XBL — master-constants §1 */
            const val EVENT_CHANNEL_PORT = 55741

            /**
             * Live-view channel TCP port (0xD9BE). [H] FCW, LFJ, FJH, XPN, XBL — master-constants §1
             * Opened LAZILY — only after the camera has been placed in IMAGE_LIVE_VIEW mode
             * (FunctionMode = 22). See docs/protocol/transfer-liveview.md §6g.
             */
            const val LIVEVIEW_CHANNEL_PORT = 55742

            /** Non-blocking connect timeout in milliseconds. [H] FJH, LFJ, XPN — master-constants §1 */
            const val CONNECT_TIMEOUT_MS = 1000

            private const val TAG = "CameraWifiConnector"
        }

        private val _state = MutableStateFlow<CameraWifiState>(CameraWifiState.Idle)
        override val state: StateFlow<CameraWifiState> = _state.asStateFlow()

        private val connectivityManager: ConnectivityManager by lazy {
            context.getSystemService(ConnectivityManager::class.java)
        }

        // Guarded by the single-caller contract; replaced per requestCameraNetwork invocation.
        @Volatile private var activeCallback: ConnectivityManager.NetworkCallback? = null

        // Original socket kept alive to maintain the network binding until release().
        @Volatile private var activeSocket: Socket? = null

        // Network delivered by onAvailable; set once per requestCameraNetwork and cleared on release.
        // Used by findNetwork to avoid OEM-flaky allNetworks re-scanning.
        @Volatile private var activeNetwork: Network? = null

        // Ensures release() is idempotent — second call is a no-op.
        private val released = AtomicBoolean(false)

        // cancel-safe: CompletableDeferred resolves once; no shared mutable state mutated after resolve.
        override suspend fun requestCameraNetwork(credentials: CameraWifiCredentials): Result<CameraNetworkHandle> =
            withContext(ioDispatcher) {
                // Permission check — CHANGE_WIFI_STATE (always) + NEARBY_WIFI_DEVICES (API 33+).
                // Do NOT check or request ACCESS_FINE_LOCATION. See privacy-local-first.md.
                val changeWifi =
                    ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.CHANGE_WIFI_STATE,
                    )
                if (changeWifi != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Timber.tag(TAG).d("requestCameraNetwork: CHANGE_WIFI_STATE not granted")
                    _state.value = CameraWifiState.Error(CameraWifiError.PermissionDenied)
                    return@withContext Result.failure(
                        SecurityException("CHANGE_WIFI_STATE permission not granted"),
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val nearbyWifi =
                        ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.NEARBY_WIFI_DEVICES,
                        )
                    if (nearbyWifi != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        Timber.tag(TAG).d("requestCameraNetwork: NEARBY_WIFI_DEVICES not granted")
                        _state.value = CameraWifiState.Error(CameraWifiError.PermissionDenied)
                        return@withContext Result.failure(
                            SecurityException("NEARBY_WIFI_DEVICES permission not granted"),
                        )
                    }
                }

                _state.value = CameraWifiState.RequestingNetwork
                Timber.tag(TAG).d("requestCameraNetwork: RequestingNetwork (ssid.hash=%d)", credentials.ssid.hashCode())

                val deferred = CompletableDeferred<Result<CameraNetworkHandle>>()

                // Capture passphrase in a local val for smart-cast across the module boundary.
                val passphrase = credentials.passphrase
                val specifierBuilder =
                    WifiNetworkSpecifier
                        .Builder()
                        .setSsid(credentials.ssid)
                val specifier =
                    if (passphrase != null) {
                        specifierBuilder.setWpa2Passphrase(passphrase).build()
                    } else {
                        specifierBuilder.build()
                    }

                val request =
                    NetworkRequest
                        .Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .setNetworkSpecifier(specifier)
                        .build()

                val networkCallback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            // Cache the exact Network object so findNetwork can return it directly
                            // without an OEM-flaky allNetworks re-scan.
                            activeNetwork = network
                            _state.value = CameraWifiState.NetworkAvailable
                            Timber.tag(TAG).d("requestCameraNetwork: NetworkAvailable")
                            // Use network id string as correlation key — never the SSID/MAC.
                            deferred.complete(Result.success(CameraNetworkHandle(network.networkHandle.toString())))
                        }

                        override fun onUnavailable() {
                            Timber
                                .tag(
                                    TAG,
                                ).d("requestCameraNetwork: UserRejectedNetworkRequest (unavailable or dismissed)")
                            _state.value = CameraWifiState.Error(CameraWifiError.UserRejectedNetworkRequest)
                            deferred.complete(
                                Result.failure(
                                    IllegalStateException("Camera Wi-Fi network unavailable or rejected"),
                                ),
                            )
                        }

                        override fun onLost(network: Network) {
                            Timber.tag(TAG).d("requestCameraNetwork: NetworkLost")
                            // Only emit NetworkLost when a session is established (not during initial request).
                            if (_state.value is CameraWifiState.Connected) {
                                _state.value = CameraWifiState.Error(CameraWifiError.NetworkLost)
                            }
                        }
                    }

                // Unregister any stale callback from a previous requestCameraNetwork call
                // before registering a new one; avoids a callback leak on repeated calls.
                safeUnregisterCallback()
                released.set(false)
                activeCallback = networkCallback
                var keepCallback = false
                try {
                    connectivityManager.requestNetwork(request, networkCallback)
                    val result = deferred.await()
                    keepCallback = result.isSuccess
                    result
                } finally {
                    if (!keepCallback) {
                        safeUnregisterCallback()
                    }
                }
            }

        // cancel-safe: socket creation and connect are synchronous within withContext; cancellation closes the scope cleanly.
        override suspend fun openBoundSocket(
            handle: CameraNetworkHandle,
            endpoint: CameraEndpoint,
        ): Result<OwnedSocketHandle> =
            withContext(ioDispatcher) {
                _state.value = CameraWifiState.BindingSocket
                Timber.tag(TAG).d("openBoundSocket: BindingSocket -> %s:%d", endpoint.host, endpoint.port)

                // Find the active Android Network matching the handle's network handle id.
                val network = findNetwork(handle)
                if (network == null) {
                    val detail = "No active Network matching handle; network may have been lost"
                    _state.value = CameraWifiState.Error(CameraWifiError.SocketBindFailed(detail))
                    safeUnregisterCallback()
                    return@withContext Result.failure(IllegalStateException(detail))
                }

                val socket = Socket()
                try {
                    // Bind the socket to the camera Wi-Fi network so all traffic routes through it.
                    // This is the production routing mechanism — Rust must NOT open its own TcpStream.
                    network.bindSocket(socket)
                } catch (e: Exception) {
                    val detail = "bindSocket failed: ${e.javaClass.simpleName}"
                    Timber.tag(TAG).d("openBoundSocket: SocketBindFailed (%s)", detail)
                    _state.value = CameraWifiState.Error(CameraWifiError.SocketBindFailed(detail))
                    socket.close()
                    safeUnregisterCallback()
                    return@withContext Result.failure(e)
                }

                _state.value = CameraWifiState.ConnectingSocket
                Timber.tag(TAG).d("openBoundSocket: ConnectingSocket")

                try {
                    // TCP_NODELAY + SO_KEEPALIVE MUST be set before connect per master-constants §1 [H] FJH, LFJ, XPN.
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
                } catch (e: Exception) {
                    val detail = "connect failed: ${e.javaClass.simpleName}"
                    Timber.tag(TAG).d("openBoundSocket: SocketConnectFailed (%s)", detail)
                    _state.value = CameraWifiState.Error(CameraWifiError.SocketConnectFailed(detail))
                    socket.close()
                    safeUnregisterCallback()
                    return@withContext Result.failure(e)
                }

                _state.value = CameraWifiState.HandingOffSocketFd
                Timber.tag(TAG).d("openBoundSocket: HandingOffSocketFd")

                // DUP AND OWN:
                // ParcelFileDescriptor.fromSocket(socket) wraps the socket's fd; detachFd() creates
                // an independent duplicate that the caller must pass to the native bridge once.
                //
                // OWNERSHIP NOTE: Android retains the original 'socket' object (stored in activeSocket)
                // to keep the Network binding alive until release(). The detached fd is an independent
                // dup. Rust borrows and dups it synchronously; the camera data adapter closes this
                // detached fd after the native bridge returns. Rust closes only its own dup.
                //
                // See: docs/rust/fd-ownership.md, docs/adr/0002-wifi-socket-fd-handoff.md §Socket Rules
                val ownedFd =
                    try {
                        ParcelFileDescriptor.fromSocket(socket).detachFd()
                    } catch (e: Exception) {
                        val detail = "fd dup failed: ${e.javaClass.simpleName}"
                        Timber.tag(TAG).d("openBoundSocket: UnexpectedError during fd handoff (%s)", detail)
                        _state.value = CameraWifiState.Error(CameraWifiError.UnexpectedError(e))
                        socket.close()
                        safeUnregisterCallback()
                        return@withContext Result.failure(e)
                    }

                // Keep original socket alive — the Network binding is tied to it.
                activeSocket = socket

                _state.value = CameraWifiState.Connected
                Timber.tag(TAG).d("openBoundSocket: Connected")

                Result.success(OwnedSocketHandle(ownedFd))
            }

        // cancel-safe: socket creation and connect are synchronous within withContext; cancellation closes the scope cleanly.
        override suspend fun openEventSocket(handle: CameraNetworkHandle): Result<OwnedSocketHandle> =
            withContext(ioDispatcher) {
                // The event channel is opened LAZILY — only after InitiateOpenCapture (0x101C) causes
                // the camera to start listening on EVENT_CHANNEL_PORT. Do NOT open this socket eagerly.
                //
                // EVENT_CHANNEL_PORT = 55741 (0xD9BD). [H] FCW, LFJ, FJH, XPN, XBL — master-constants §1
                // Socket config: TCP_NODELAY=true, SO_KEEPALIVE=true, 1 s connect timeout (same as command channel).
                val eventEndpoint =
                    CameraEndpoint(
                        host = CAMERA_AP_IP,
                        port = EVENT_CHANNEL_PORT,
                    )

                _state.value = CameraWifiState.EventSocketRequested
                Timber
                    .tag(
                        TAG,
                    ).d("openEventSocket: EventSocketRequested -> %s:%d", eventEndpoint.host, eventEndpoint.port)

                val network = findNetwork(handle)
                if (network == null) {
                    val detail = "No active Network matching handle; network may have been lost"
                    _state.value = CameraWifiState.Error(CameraWifiError.SocketBindFailed(detail))
                    return@withContext Result.failure(IllegalStateException(detail))
                }

                val socket = Socket()
                try {
                    network.bindSocket(socket)
                } catch (e: Exception) {
                    val detail = "event-socket bindSocket failed: ${e.javaClass.simpleName}"
                    Timber.tag(TAG).d("openEventSocket: SocketBindFailed (%s)", detail)
                    _state.value = CameraWifiState.Error(CameraWifiError.SocketBindFailed(detail))
                    socket.close()
                    return@withContext Result.failure(e)
                }

                _state.value = CameraWifiState.EventSocketBound
                Timber.tag(TAG).d("openEventSocket: EventSocketBound")

                try {
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.connect(InetSocketAddress(eventEndpoint.host, eventEndpoint.port), CONNECT_TIMEOUT_MS)
                } catch (e: Exception) {
                    val detail = "event-socket connect failed: ${e.javaClass.simpleName}"
                    Timber.tag(TAG).d("openEventSocket: SocketConnectFailed (%s)", detail)
                    _state.value = CameraWifiState.Error(CameraWifiError.SocketConnectFailed(detail))
                    socket.close()
                    return@withContext Result.failure(e)
                }

                // DUP AND OWN (identical discipline to openBoundSocket — M07 command-channel pattern):
                // ParcelFileDescriptor.fromSocket(socket) wraps the socket's fd; detachFd() creates
                // an independent duplicate transferred to the caller for a native bridge call.
                // Rust borrows and dups it synchronously; the camera data adapter closes this
                // detached fd after the native bridge returns. Rust closes only its own dup.
                // The underlying 'socket' object is closed below — Android does NOT retain the event socket
                // because the event channel does not keep the Network binding alive (the command channel
                // socket in activeSocket already does that).
                //
                // fd ownership: caller must pass the detached fd to the native bridge exactly once.
                // See: docs/rust/fd-ownership.md, docs/adr/0002-wifi-socket-fd-handoff.md §Socket Rules
                val ownedFd =
                    try {
                        ParcelFileDescriptor.fromSocket(socket).detachFd()
                    } catch (e: Exception) {
                        val detail = "event-socket fd dup failed: ${e.javaClass.simpleName}"
                        Timber.tag(TAG).d("openEventSocket: UnexpectedError during fd handoff (%s)", detail)
                        _state.value = CameraWifiState.Error(CameraWifiError.UnexpectedError(e))
                        socket.close()
                        return@withContext Result.failure(e)
                    }

                // Event socket is NOT stored in activeSocket — the command channel binding keeps the
                // network alive. Close Android's own reference to the event socket now.
                try {
                    socket.close()
                } catch (_: Exception) {
                    // Best-effort; fd has already been dup'd and detached.
                }

                _state.value = CameraWifiState.EventSocketHandedOff
                Timber.tag(TAG).d("openEventSocket: EventSocketHandedOff")

                Result.success(OwnedSocketHandle(ownedFd))
            }

        // cancel-safe: socket creation and connect are synchronous within withContext; cancellation closes the scope cleanly.
        override suspend fun openLiveViewSocket(handle: CameraNetworkHandle): Result<OwnedSocketHandle> =
            withContext(ioDispatcher) {
                // The live-view channel is opened LAZILY — only after the camera has been placed in
                // IMAGE_LIVE_VIEW mode (FunctionMode = 22). Do NOT open this socket eagerly.
                //
                // LIVEVIEW_CHANNEL_PORT = 55742 (0xD9BE). [H] FCW, LFJ, FJH, XPN, XBL — master-constants §1
                // Socket config: TCP_NODELAY=true, SO_KEEPALIVE=true, 1 s connect timeout (same as command/event channels).
                val liveViewEndpoint =
                    CameraEndpoint(
                        host = CAMERA_AP_IP,
                        port = LIVEVIEW_CHANNEL_PORT,
                    )

                _state.value = CameraWifiState.LiveViewSocketRequested
                Timber
                    .tag(TAG)
                    .d("openLiveViewSocket: LiveViewSocketRequested -> host=[redacted]:%d", liveViewEndpoint.port)

                val network = findNetwork(handle)
                if (network == null) {
                    val detail = "No active Network matching handle; network may have been lost"
                    _state.value = CameraWifiState.Error(CameraWifiError.SocketBindFailed(detail))
                    return@withContext Result.failure(IllegalStateException(detail))
                }

                val socket = Socket()
                try {
                    network.bindSocket(socket)
                } catch (e: Exception) {
                    val detail = "liveview-socket bindSocket failed: ${e.javaClass.simpleName}"
                    Timber.tag(TAG).d("openLiveViewSocket: SocketBindFailed (%s)", detail)
                    _state.value = CameraWifiState.Error(CameraWifiError.SocketBindFailed(detail))
                    socket.close()
                    return@withContext Result.failure(e)
                }

                _state.value = CameraWifiState.LiveViewSocketBound
                Timber.tag(TAG).d("openLiveViewSocket: LiveViewSocketBound")

                try {
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.connect(InetSocketAddress(liveViewEndpoint.host, liveViewEndpoint.port), CONNECT_TIMEOUT_MS)
                } catch (e: Exception) {
                    val detail = "liveview-socket connect failed: ${e.javaClass.simpleName}"
                    Timber.tag(TAG).d("openLiveViewSocket: SocketConnectFailed (%s)", detail)
                    _state.value = CameraWifiState.Error(CameraWifiError.SocketConnectFailed(detail))
                    socket.close()
                    return@withContext Result.failure(e)
                }

                // DUP AND OWN (identical discipline to openEventSocket — M15 event-channel pattern):
                // ParcelFileDescriptor.fromSocket(socket) wraps the socket's fd; detachFd() creates
                // an independent duplicate transferred to the caller for a native bridge call.
                // Rust borrows and dups it synchronously; the camera data adapter closes this
                // detached fd after the native bridge returns. Rust closes only its own dup when the
                // live-view session stops.
                // The underlying 'socket' object is closed below — Android does NOT retain the live-view
                // socket because it does not keep the Network binding alive (the command channel socket
                // in activeSocket already does that).
                //
                // fd ownership: caller must pass the detached fd to the native bridge exactly once.
                // See: docs/rust/fd-ownership.md, docs/adr/0002-wifi-socket-fd-handoff.md §Socket Rules
                val ownedFd =
                    try {
                        ParcelFileDescriptor.fromSocket(socket).detachFd()
                    } catch (e: Exception) {
                        val detail = "liveview-socket fd dup failed: ${e.javaClass.simpleName}"
                        Timber.tag(TAG).d("openLiveViewSocket: UnexpectedError during fd handoff (%s)", detail)
                        _state.value = CameraWifiState.Error(CameraWifiError.UnexpectedError(e))
                        socket.close()
                        return@withContext Result.failure(e)
                    }

                // Live-view socket is NOT stored in activeSocket — the command channel binding keeps the
                // network alive. Close Android's own reference to the live-view socket now.
                try {
                    socket.close()
                } catch (_: Exception) {
                    // Best-effort; fd has already been dup'd and detached.
                }

                _state.value = CameraWifiState.LiveViewSocketHandedOff
                Timber.tag(TAG).d("openLiveViewSocket: LiveViewSocketHandedOff")

                Result.success(OwnedSocketHandle(ownedFd))
            }

        // cancel-safe: idempotent cleanup; second call is a no-op; no suspension points after state transition.
        override suspend fun release(handle: CameraNetworkHandle) =
            withContext(ioDispatcher) {
                if (!released.compareAndSet(false, true)) {
                    // Already released — idempotent no-op.
                    Timber.tag(TAG).d("release: already released, no-op")
                    return@withContext
                }

                _state.value = CameraWifiState.Releasing
                Timber.tag(TAG).d("release: Releasing")

                safeUnregisterCallback()

                try {
                    activeSocket?.close()
                } catch (e: Exception) {
                    Timber.tag(TAG).d("release: socket close error: %s", e.javaClass.simpleName)
                } finally {
                    activeSocket = null
                }

                _state.value = CameraWifiState.Closed
                Timber.tag(TAG).d("release: Closed")
            }

        /**
         * Unregisters the active network callback. Guaranteed to run on release() and on terminal Error.
         * Safe to call multiple times — exceptions are swallowed and logged.
         */
        private fun safeUnregisterCallback() {
            try {
                activeCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            } catch (e: Exception) {
                Timber.tag(TAG).d("safeUnregisterCallback: %s", e.javaClass.simpleName)
            } finally {
                activeCallback = null
                activeNetwork = null
            }
        }

        /**
         * Returns the [Network] for the given handle.
         *
         * Uses [activeNetwork] cached from [ConnectivityManager.NetworkCallback.onAvailable] so the
         * result is deterministic and avoids an OEM-flaky [ConnectivityManager.allNetworks] scan.
         * Falls back to the scan only when the cache is absent (e.g. after process restore).
         *
         * Returns null if no active network matches (e.g. network was lost between request and socket open).
         */
        private fun findNetwork(handle: CameraNetworkHandle): Network? {
            val cached = activeNetwork
            if (cached != null) return cached
            // Fallback: scan allNetworks if cache was cleared unexpectedly (e.g. process restore).
            val targetId = handle.value.toLongOrNull() ?: return null
            return connectivityManager.allNetworks.firstOrNull { it.networkHandle == targetId }
        }
    }
