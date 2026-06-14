package dev.po4yka.frameport.core.testing

import dev.po4yka.frameport.camera.api.CameraEndpoint
import dev.po4yka.frameport.camera.api.CameraNetworkHandle
import dev.po4yka.frameport.camera.api.CameraWifiConnector
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.CameraWifiError
import dev.po4yka.frameport.camera.api.CameraWifiState
import dev.po4yka.frameport.camera.api.OwnedSocketHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test-harness implementation of [CameraWifiConnector].
 *
 * Pure JVM — no Android framework types. Suitable for use in :core:testing (kotlin.jvm module)
 * and in any Android module's unit tests via testImplementation(projects.core.testing).
 *
 * Usage patterns:
 *
 * 1. Happy-path (default): call requestCameraNetwork / openBoundSocket / release normally —
 *    each emits the expected sub-sequence of states.
 *
 * 2. Pre-arm an error: call [armError] before the method under test, then invoke the interface
 *    method — it will emit [CameraWifiState.Error] with the armed error instead of the success path.
 *
 * 3. Direct state injection: call [emit] to push any [CameraWifiState] directly (e.g. for testing
 *    observers that collect state).
 *
 * 4. Scenario helpers: [driveHappyPath] emits the full success sequence in one call.
 *
 * All suspend overrides carry cancel-safety annotations matching the production interface.
 */
class FakeCameraWifiConnector : CameraWifiConnector {
    private val _state = MutableStateFlow<CameraWifiState>(CameraWifiState.Idle)
    override val state: StateFlow<CameraWifiState> = _state.asStateFlow()

    /** Fake fd used in happy-path responses. -1 is invalid/sentinel; tests can override via [armFd]. */
    private var armedFd: Int = FAKE_FD

    /** When non-null, the next interface call will emit Error(armedError) instead of success states. */
    @Volatile private var armedError: CameraWifiError? = null

    private val releasedOnce = AtomicBoolean(false)

    // cancel-safe: no real suspension; state transitions are immediate.
    override suspend fun requestCameraNetwork(credentials: CameraWifiCredentials): Result<CameraNetworkHandle> {
        val error = armedError
        if (error != null) {
            armedError = null
            _state.value = CameraWifiState.Error(error)
            return Result.failure(IllegalStateException("FakeCameraWifiConnector: armed error: $error"))
        }
        _state.value = CameraWifiState.RequestingNetwork
        _state.value = CameraWifiState.NetworkAvailable
        return Result.success(CameraNetworkHandle(FAKE_NETWORK_HANDLE))
    }

    // cancel-safe: no real suspension; state transitions are immediate.
    override suspend fun openBoundSocket(
        handle: CameraNetworkHandle,
        endpoint: CameraEndpoint,
    ): Result<OwnedSocketHandle> {
        val error = armedError
        if (error != null) {
            armedError = null
            _state.value = CameraWifiState.Error(error)
            return Result.failure(IllegalStateException("FakeCameraWifiConnector: armed error: $error"))
        }
        _state.value = CameraWifiState.BindingSocket
        _state.value = CameraWifiState.ConnectingSocket
        _state.value = CameraWifiState.HandingOffSocketFd
        _state.value = CameraWifiState.Connected
        return Result.success(OwnedSocketHandle(armedFd))
    }

    // cancel-safe: no real suspension; state transitions are immediate.
    override suspend fun openLiveViewSocket(handle: CameraNetworkHandle): Result<OwnedSocketHandle> {
        val error = armedError
        if (error != null) {
            armedError = null
            _state.value = CameraWifiState.Error(error)
            return Result.failure(IllegalStateException("FakeCameraWifiConnector: armed error: $error"))
        }
        _state.value = CameraWifiState.LiveViewSocketRequested
        _state.value = CameraWifiState.LiveViewSocketBound
        _state.value = CameraWifiState.LiveViewSocketHandedOff
        return Result.success(OwnedSocketHandle(armedFd))
    }

    // cancel-safe: no real suspension; state transitions are immediate.
    override suspend fun openEventSocket(handle: CameraNetworkHandle): Result<OwnedSocketHandle> {
        val error = armedError
        if (error != null) {
            armedError = null
            _state.value = CameraWifiState.Error(error)
            return Result.failure(IllegalStateException("FakeCameraWifiConnector: armed error: $error"))
        }
        _state.value = CameraWifiState.EventSocketRequested
        _state.value = CameraWifiState.EventSocketBound
        _state.value = CameraWifiState.EventSocketHandedOff
        return Result.success(OwnedSocketHandle(armedFd))
    }

    // cancel-safe: idempotent; second call is a no-op; no real suspension.
    override suspend fun release(handle: CameraNetworkHandle) {
        if (!releasedOnce.compareAndSet(false, true)) {
            // Idempotent — second call is a no-op; Closed already emitted.
            return
        }
        _state.value = CameraWifiState.Releasing
        _state.value = CameraWifiState.Closed
    }

    // ─── Test harness API ────────────────────────────────────────────────────

    /**
     * Directly emit any [CameraWifiState] value to [state]. Use to set up an observer test
     * without going through the full flow of interface methods.
     */
    fun emit(newState: CameraWifiState) {
        _state.value = newState
    }

    /**
     * Pre-arm an error outcome. The next call to [requestCameraNetwork] or [openBoundSocket]
     * will emit [CameraWifiState.Error] with this error and return [Result.failure] instead of
     * executing the success path.
     */
    fun armError(error: CameraWifiError) {
        armedError = error
    }

    /**
     * Override the fake fd returned by [openBoundSocket] on the happy path. Default is [FAKE_FD].
     */
    fun armFd(fd: Int) {
        armedFd = fd
    }

    /**
     * Resets fake state to Idle and clears all armed values. Call between tests.
     */
    fun reset() {
        armedError = null
        armedFd = FAKE_FD
        releasedOnce.set(false)
        _state.value = CameraWifiState.Idle
    }

    /**
     * Drives the full happy-path state sequence:
     * Idle → RequestingNetwork → NetworkAvailable → BindingSocket → ConnectingSocket
     * → HandingOffSocketFd → Connected
     *
     * Does NOT call through the suspend interface methods; emits each leaf directly.
     * Useful for testing collectors that observe the full sequence.
     */
    fun driveHappyPath() {
        _state.value = CameraWifiState.Idle
        _state.value = CameraWifiState.RequestingNetwork
        _state.value = CameraWifiState.NetworkAvailable
        _state.value = CameraWifiState.BindingSocket
        _state.value = CameraWifiState.ConnectingSocket
        _state.value = CameraWifiState.HandingOffSocketFd
        _state.value = CameraWifiState.Connected
    }

    /**
     * Drives the release sequence: Releasing → Closed.
     */
    fun driveRelease() {
        _state.value = CameraWifiState.Releasing
        _state.value = CameraWifiState.Closed
    }

    /**
     * Emits [CameraWifiState.Error] with the given error directly (without going through a method call).
     */
    fun failWith(error: CameraWifiError) {
        _state.value = CameraWifiState.Error(error)
    }

    companion object {
        /** Sentinel fd used in test happy-path responses. -1 is invalid in production; fine for fakes. */
        const val FAKE_FD = -1

        /** Sentinel network handle string used by the fake. */
        const val FAKE_NETWORK_HANDLE = "fake-network-handle-0"
    }
}
