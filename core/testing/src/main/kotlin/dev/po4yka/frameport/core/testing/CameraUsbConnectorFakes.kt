package dev.po4yka.frameport.core.testing

import dev.po4yka.frameport.camera.api.CameraUsbConnector
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.UsbDeviceRef
import dev.po4yka.frameport.camera.api.UsbSessionError
import dev.po4yka.frameport.camera.api.UsbSessionState
import dev.po4yka.frameport.camera.api.UsbTransportHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test-harness implementation of [CameraUsbConnector].
 *
 * Pure JVM — no Android framework types. Suitable for use in :core:testing (kotlin.jvm module)
 * and in any Android module's unit tests via testImplementation(projects.core.testing).
 *
 * Usage patterns:
 *
 * 1. Happy-path (default): call requestPermission / openSession / close normally —
 *    each emits the expected sub-sequence of states.
 *
 * 2. Pre-arm an error: call [armError] before the method under test, then invoke the interface
 *    method — it will emit [UsbSessionState.Error] with the armed error instead of success states.
 *
 * 3. Direct state injection: call [emit] to push any [UsbSessionState] directly.
 *
 * 4. Scenario helpers: [driveHappyPath] emits the full success sequence in one call.
 *
 * 5. Detach simulation: call [simulateDetach] to test mid-session device removal at any phase.
 *
 * All suspend overrides carry cancel-safety annotations matching the production interface.
 */
class FakeCameraUsbConnector : CameraUsbConnector {
    private val _state = MutableStateFlow<UsbSessionState>(UsbSessionState.Disconnected)
    override val state: StateFlow<UsbSessionState> = _state.asStateFlow()

    /** When non-null, the next interface call will emit Error(armedError) instead of success states. */
    @Volatile private var armedError: UsbSessionError? = null

    /** Fake session id returned by the happy-path openSession response. Default is [FAKE_SESSION_ID]. */
    private var armedSessionId: Long = FAKE_SESSION_ID

    private val closedOnce = AtomicBoolean(false)

    /** Records all [requestPermission] call arguments for assertions. */
    val permissionRequests = mutableListOf<UsbDeviceRef>()

    /** Records all [openSession] call argument pairs for assertions. */
    val openSessionCalls = mutableListOf<Pair<UsbDeviceRef, ByteArray>>()

    /** Records all [close] call arguments for assertions. */
    val closeCalls = mutableListOf<UsbTransportHandle>()

    // cancel-safe: no real suspension; state transitions are immediate.
    override suspend fun requestPermission(device: UsbDeviceRef): Result<Unit> {
        permissionRequests.add(device)
        val error = armedError
        if (error != null) {
            armedError = null
            _state.value = UsbSessionState.Error(error = error, message = "FakeCameraUsbConnector: armed error")
            return Result.failure(IllegalStateException("FakeCameraUsbConnector: armed error: $error"))
        }
        _state.value = UsbSessionState.PermissionPending
        _state.value = UsbSessionState.OpeningDevice
        return Result.success(Unit)
    }

    // cancel-safe: no real suspension; state transitions are immediate.
    override suspend fun openSession(
        device: UsbDeviceRef,
        descriptors: ByteArray,
    ): Result<UsbTransportHandle> {
        openSessionCalls.add(device to descriptors)
        val error = armedError
        if (error != null) {
            armedError = null
            _state.value = UsbSessionState.Error(error = error, message = "FakeCameraUsbConnector: armed error")
            return Result.failure(IllegalStateException("FakeCameraUsbConnector: armed error: $error"))
        }
        _state.value = UsbSessionState.OpeningDevice
        _state.value = UsbSessionState.DeviceOpen
        _state.value = UsbSessionState.FdHandoff
        _state.value = UsbSessionState.RustTransportOpen
        _state.value = UsbSessionState.PtpSessionOpening
        val handle = UsbTransportHandle(SessionId(armedSessionId))
        _state.value = UsbSessionState.UsbSessionReady(handle)
        return Result.success(handle)
    }

    // cancel-safe: idempotent; second call is a no-op; no real suspension.
    override suspend fun close(handle: UsbTransportHandle) {
        closeCalls.add(handle)
        if (!closedOnce.compareAndSet(false, true)) {
            // Idempotent — second call is a no-op; Closed already emitted.
            return
        }
        _state.value = UsbSessionState.Closing
        _state.value = UsbSessionState.Closed
    }

    // ─── Test harness API ─────────────────────────────────────────────────────

    /**
     * Directly emit any [UsbSessionState] value to [state]. Use to set up an observer test
     * without going through the full flow of interface methods.
     */
    fun emit(newState: UsbSessionState) {
        _state.value = newState
    }

    /**
     * Pre-arm an error outcome. The next call to [requestPermission] or [openSession]
     * will emit [UsbSessionState.Error] with this error and return [Result.failure].
     */
    fun armError(error: UsbSessionError) {
        armedError = error
    }

    /**
     * Override the fake session id returned by [openSession] on the happy path.
     * Default is [FAKE_SESSION_ID].
     */
    fun armSessionId(sessionId: Long) {
        armedSessionId = sessionId
    }

    /**
     * Simulate a device detach event.
     *
     * Emits [UsbSessionState.Error] with [UsbSessionError.DeviceDetachedDuringSession] or
     * [UsbSessionError.DeviceDetachedBeforePermission] depending on the current state.
     * This mirrors the production logic in [AndroidCameraUsbConnector.handleDetached].
     */
    fun simulateDetach() {
        when (_state.value) {
            is UsbSessionState.PermissionPending -> {
                _state.value =
                    UsbSessionState.Error(
                        error = UsbSessionError.DeviceDetachedBeforePermission,
                        message = "Device detached while permission dialog was pending",
                    )
            }

            else -> {
                _state.value =
                    UsbSessionState.Error(
                        error = UsbSessionError.DeviceDetachedDuringSession,
                        message = "Device detached during active session (simulated)",
                    )
            }
        }
    }

    /**
     * Resets fake state to Disconnected and clears all armed values and recorded calls.
     * Call between tests.
     */
    fun reset() {
        armedError = null
        armedSessionId = FAKE_SESSION_ID
        closedOnce.set(false)
        permissionRequests.clear()
        openSessionCalls.clear()
        closeCalls.clear()
        _state.value = UsbSessionState.Disconnected
    }

    /**
     * Drives the full happy-path state sequence without going through the suspend methods.
     * Useful for testing collectors that observe the full sequence.
     */
    fun driveHappyPath(sessionId: Long = FAKE_SESSION_ID) {
        _state.value = UsbSessionState.Disconnected
        _state.value =
            UsbSessionState.DeviceDetected(
                UsbDeviceRef(deviceKey = "fake:key:0", displayName = "USB Device [fake]"),
            )
        _state.value = UsbSessionState.PermissionPending
        _state.value = UsbSessionState.OpeningDevice
        _state.value = UsbSessionState.DeviceOpen
        _state.value = UsbSessionState.FdHandoff
        _state.value = UsbSessionState.RustTransportOpen
        _state.value = UsbSessionState.PtpSessionOpening
        _state.value = UsbSessionState.UsbSessionReady(UsbTransportHandle(SessionId(sessionId)))
    }

    /**
     * Drives the close sequence: Closing → Closed.
     */
    fun driveClose() {
        _state.value = UsbSessionState.Closing
        _state.value = UsbSessionState.Closed
    }

    /**
     * Emits [UsbSessionState.Error] with the given error directly.
     */
    fun failWith(error: UsbSessionError) {
        _state.value =
            UsbSessionState.Error(error = error, message = "FakeCameraUsbConnector: ${error::class.simpleName}")
    }

    companion object {
        /** Sentinel session id used in test happy-path responses. */
        const val FAKE_SESSION_ID = 99L
    }
}
