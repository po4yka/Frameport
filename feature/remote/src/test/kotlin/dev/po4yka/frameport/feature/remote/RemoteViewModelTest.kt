@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.po4yka.frameport.feature.remote

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.RemoteCaptureError
import dev.po4yka.frameport.camera.api.RemoteCaptureRequest
import dev.po4yka.frameport.camera.api.RemoteCaptureState
import dev.po4yka.frameport.camera.api.ShutterAction
import dev.po4yka.frameport.camera.domain.AllowlistRemoteCapabilityChecker
import dev.po4yka.frameport.camera.domain.RemoteCaptureUseCase
import dev.po4yka.frameport.core.testing.FakeFujiBleClient
import dev.po4yka.frameport.core.testing.FakeFujiNativeSdk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Builds a [RemoteCaptureUseCase] backed by [FakeFujiBleClient] and
 * [FakeFujiNativeSdk] using [AllowlistRemoteCapabilityChecker] (X-T5 passes).
 *
 * [testDispatcher] is injected as the IO dispatcher so flowOn executes on the
 * controlled test dispatcher rather than a real IO thread.
 */
private fun buildUseCase(
    fakeBle: FakeFujiBleClient,
    fakeSdk: FakeFujiNativeSdk,
    testDispatcher: CoroutineDispatcher,
): RemoteCaptureUseCase =
    RemoteCaptureUseCase(
        fujiBleClient = fakeBle,
        fujiNativeSdk = fakeSdk,
        capabilityChecker = AllowlistRemoteCapabilityChecker,
        ioDispatcher = testDispatcher,
    )

// ─── Test class ───────────────────────────────────────────────────────────────

/**
 * Unit tests for [RemoteViewModel].
 *
 * Scope: ViewModel logic only — state mapping, action dispatch, error surface, and
 * retry. No Android platform types (BluetoothGatt, JNI) under test.
 *
 * Dispatcher strategy — [StandardTestDispatcher]:
 * [RemoteViewModel.dispatchBleShutter] calls `launchIn(viewModelScope)`. The
 * viewModelScope uses `Dispatchers.Main` (replaced by the test dispatcher in
 * [setUp]). With [StandardTestDispatcher] (not Unconfined), the launched coroutine
 * is SCHEDULED but does NOT run until [advanceUntilIdle] is called. This lets
 * Turbine's collector start before the flow emits, so intermediate StateFlow
 * transitions are observable.
 *
 * The use case applies `flowOn(ioDispatcher)`. Since we inject [StandardTestDispatcher]
 * as both Main and IO, both the launchIn coroutine and the flowOn worker share
 * the same virtual clock and are driven forward by [advanceUntilIdle].
 *
 * StateFlow deduplication:
 * [RemoteCaptureUseCase] always emits [RemoteCaptureState.Idle] as its first item.
 * Since [RemoteViewModel.uiState] is already [RemoteShutterUiState.Idle] at startup,
 * this maps to an identical value and StateFlow's deduplication suppresses the
 * emission. Tests must NOT expect an "Idle prefix" item — only subsequent
 * state-changes are visible to collectors.
 *
 * Notes on RemoteShutterUiState.Released:
 * [RemoteShutterUiState.Released] is defined in the sealed interface but has no
 * current producer: `ShutterAction.Release → RemoteCaptureState.Idle →
 * RemoteShutterUiState.Idle`. The Release action therefore causes the ViewModel
 * to (potentially) emit Idle — which is also deduplicated if current == Idle.
 * TODO(M16): add RemoteCaptureState.Released to the state machine and update
 * the Release-action tests accordingly.
 */
class RemoteViewModelTest {
    // A FRESH StandardTestDispatcher per test (assigned in setUp, not a shared field):
    // the ViewModel's viewModelScope coroutines are not children of runTest and can
    // outlive it; a shared scheduler would let one test's leftover work pollute the
    // next. A per-test dispatcher isolates each test's scheduler.
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var fakeBle: FakeFujiBleClient
    private lateinit var fakeSdk: FakeFujiNativeSdk
    private lateinit var useCase: RemoteCaptureUseCase
    private lateinit var viewModel: RemoteViewModel

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        fakeBle = FakeFujiBleClient()
        fakeSdk = FakeFujiNativeSdk()
        useCase = buildUseCase(fakeBle, fakeSdk, testDispatcher)
        viewModel = RemoteViewModel(useCase)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun initialStateIsIdle() {
        assertEquals(RemoteShutterUiState.Idle, viewModel.uiState.value)
    }

    // ── Half-press happy path ─────────────────────────────────────────────────

    /**
     * Given: BLE is Connected and write succeeds.
     * When:  user triggers HalfPress, then [advanceUntilIdle] drains the scheduler.
     * Then:  uiState transitions to HalfPress.
     *
     * StateFlow deduplication: the use case's first Idle emission is suppressed
     * (current value is already Idle). Only the HalfPressed → HalfPress transition
     * is visible to Turbine.
     */
    @Test
    fun halfPressWhileBleConnectedEmitsHalfPress() =
        runTest(testDispatcher) {
            fakeBle.setConnectionState(BleConnectionState.Connected)

            viewModel.uiState.test {
                assertEquals(RemoteShutterUiState.Idle, awaitItem()) // initial StateFlow value

                viewModel.onAction(RemoteAction.HalfPress)
                advanceUntilIdle()

                assertEquals(RemoteShutterUiState.HalfPress, awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── Full-press happy path ─────────────────────────────────────────────────

    /**
     * Given: BLE is Connected and write succeeds.
     * When:  user triggers FullPress.
     * Then:  uiState emits FullPress then CaptureComplete (BLE path; no CapturingInProgress).
     *
     * The BLE path emits: Idle (deduplicated) → FullPressed → CaptureComplete.
     * FullPressed maps to FullPress; CaptureComplete maps to CaptureComplete.
     */
    @Test
    fun fullPressWhileBleConnectedEmitsFullPressThenCaptureComplete() =
        runTest(testDispatcher) {
            fakeBle.setConnectionState(BleConnectionState.Connected)

            viewModel.uiState.test {
                assertEquals(RemoteShutterUiState.Idle, awaitItem()) // initial

                viewModel.onAction(RemoteAction.FullPress)
                advanceUntilIdle()

                assertEquals(RemoteShutterUiState.FullPress, awaitItem())
                assertEquals(RemoteShutterUiState.CaptureComplete, awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── Release happy path ────────────────────────────────────────────────────

    /**
     * Given: BLE is Connected and write succeeds.
     * When:  user triggers Release.
     * Then:  uiState remains Idle (Release maps to RemoteCaptureState.Idle which
     *        is deduplicated by StateFlow since current value is already Idle).
     *        No extra items are emitted.
     *
     * TODO(M16): update when RemoteCaptureState.Released is added.
     */
    @Test
    fun releaseWhileBleConnectedRemainsIdleAfterWrite() =
        runTest(testDispatcher) {
            fakeBle.setConnectionState(BleConnectionState.Connected)

            viewModel.onAction(RemoteAction.Release)
            advanceUntilIdle()

            // StateFlow deduplicated Idle → Idle; final value must be Idle.
            assertEquals(RemoteShutterUiState.Idle, viewModel.uiState.value)
            // Exactly one BLE write was issued (the Release payload).
            assertEquals(1, fakeBle.writeCalls.size)
        }

    // ── Half → Full → Release ordered sequence ────────────────────────────────

    /**
     * Given: BLE is Connected and all writes succeed.
     * When:  user triggers HalfPress → FullPress → Release in order.
     * Then:  uiState emits HalfPress → FullPress → CaptureComplete in order.
     *        After Release the value is Idle (deduplicated, no extra emission).
     *
     * This is the primary M15 remote-shutter happy-path integration test.
     * After Release the state has already transitioned to CaptureComplete (from
     * the prior FullPress); the Release maps to Idle so a new Idle emission occurs
     * (distinct from CaptureComplete). We verify the terminal Idle via .value
     * after the turbine block.
     */
    @Test
    fun halfThenFullThenReleaseEmitsExpectedStatesInOrder() =
        runTest(testDispatcher) {
            fakeBle.setConnectionState(BleConnectionState.Connected)

            viewModel.uiState.test {
                assertEquals(RemoteShutterUiState.Idle, awaitItem()) // initial

                // Half-press
                viewModel.onAction(RemoteAction.HalfPress)
                advanceUntilIdle()
                assertEquals(RemoteShutterUiState.HalfPress, awaitItem())

                // Full-press
                viewModel.onAction(RemoteAction.FullPress)
                advanceUntilIdle()
                assertEquals(RemoteShutterUiState.FullPress, awaitItem())
                assertEquals(RemoteShutterUiState.CaptureComplete, awaitItem())

                // Release — maps to Idle (distinct from CaptureComplete → emitted)
                viewModel.onAction(RemoteAction.Release)
                advanceUntilIdle()
                assertEquals(RemoteShutterUiState.Idle, awaitItem())

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── Not-connected BLE error ───────────────────────────────────────────────

    /**
     * Given: BLE is Disconnected (default fake state).
     * When:  user triggers HalfPress.
     * Then:  uiState emits Error("BLE not connected").
     *
     * The use-case emits Idle (deduplicated) then Error(NotConnectedBle) before
     * attempting the write. ViewModel maps NotConnectedBle to
     * RemoteShutterUiState.Error("BLE not connected").
     */
    @Test
    fun halfPressWhileBleDisconnectedEmitsNotConnectedError() =
        runTest(testDispatcher) {
            // fakeBle defaults to Disconnected

            viewModel.uiState.test {
                assertEquals(RemoteShutterUiState.Idle, awaitItem()) // initial

                viewModel.onAction(RemoteAction.HalfPress)
                advanceUntilIdle()

                val errorState = awaitItem()
                assertTrue(
                    "Expected Error(BLE not connected), got $errorState",
                    errorState is RemoteShutterUiState.Error &&
                        errorState.message == "BLE not connected",
                )
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun fullPressWhileBleDisconnectedEmitsNotConnectedError() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                assertEquals(RemoteShutterUiState.Idle, awaitItem())

                viewModel.onAction(RemoteAction.FullPress)
                advanceUntilIdle()

                val errorState = awaitItem()
                assertTrue(
                    "Expected Error(BLE not connected), got $errorState",
                    errorState is RemoteShutterUiState.Error &&
                        errorState.message == "BLE not connected",
                )
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── Incompatible camera (capability gate) ─────────────────────────────────

    /**
     * Verifies that AllowlistRemoteCapabilityChecker rejects a null camera model
     * and emits IncompatibleCamera. The ViewModel stubs cameraModel = "X-T5" so the
     * gate always passes in production M15 code; this test exercises the use-case
     * gate directly via runBlocking to confirm the mapping is correct.
     *
     * Driven under [runTest] (not plain runBlocking): the use case applies
     * flowOn([testDispatcher]); runTest advances that scheduler so the cold flow
     * actually runs and completes. Plain runBlocking would never pump the
     * StandardTestDispatcher and the collect would hang forever.
     */
    @Test
    fun useCaseCapabilityGateEmitsIncompatibleCameraForNullModel() =
        runTest(testDispatcher) {
            val states: List<RemoteCaptureState> =
                useCase
                    .invoke(
                        request = RemoteCaptureRequest.BleShutter(ShutterAction.HalfPress),
                        cameraModel = null,
                    ).toList()
            assertTrue(
                "Expected Error(IncompatibleCamera) for null cameraModel, got $states",
                states.any {
                    it is RemoteCaptureState.Error &&
                        it.error is RemoteCaptureError.IncompatibleCamera
                },
            )
        }

    // ── BLE write failure ─────────────────────────────────────────────────────

    /**
     * Given: BLE is Connected but the write is armed to fail.
     * When:  user triggers FullPress.
     * Then:  uiState emits FullPress then Error("BLE write failed").
     *
     * The use-case emits Idle (deduplicated), then FullPressed (→ FullPress),
     * then Error(BleWriteFailed) after the failed write.
     */
    @Test
    fun fullPressWithBleWriteFailureEmitsBleWriteFailedError() =
        runTest(testDispatcher) {
            fakeBle.setConnectionState(BleConnectionState.Connected)
            fakeBle.armWriteResult(Result.failure(RuntimeException("gatt-error")))

            viewModel.uiState.test {
                assertEquals(RemoteShutterUiState.Idle, awaitItem()) // initial

                viewModel.onAction(RemoteAction.FullPress)
                advanceUntilIdle()

                assertEquals(RemoteShutterUiState.FullPress, awaitItem())
                val errorState = awaitItem()
                assertTrue(
                    "Expected Error(BLE write failed), got $errorState",
                    errorState is RemoteShutterUiState.Error &&
                        errorState.message == "BLE write failed",
                )
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── Bounded retry after write failure ─────────────────────────────────────

    /**
     * Given: BLE write fails for the first two FullPress attempts, then succeeds on the third.
     * When:  user triggers FullPress, Retry, FullPress, Retry, FullPress.
     * Then:  each failure surfaces as Error("BLE write failed"); each Retry resets to
     *        Idle; the third attempt succeeds and emits FullPress then CaptureComplete.
     *
     * This models the camera-busy / transient-error retry pattern. In M15 retry is
     * user-driven: Retry resets to Idle, enabling a new dispatch. No auto-retry.
     * TODO(M16): if CameraError::Busy{attempts} surfaces from Rust, add auto-retry here.
     */
    @Test
    fun busyLikeWriteFailureTriggersBoundedRetryViaRetryAction() =
        runTest(testDispatcher) {
            fakeBle.setConnectionState(BleConnectionState.Connected)

            viewModel.uiState.test {
                assertEquals(RemoteShutterUiState.Idle, awaitItem()) // initial

                // Attempt 1 — write fails
                fakeBle.armWriteResult(Result.failure(RuntimeException("busy")))
                viewModel.onAction(RemoteAction.FullPress)
                advanceUntilIdle()
                assertEquals(RemoteShutterUiState.FullPress, awaitItem())
                val error1 = awaitItem()
                assertTrue(
                    "Expected Error after attempt 1, got $error1",
                    error1 is RemoteShutterUiState.Error,
                )

                // Retry → Idle
                viewModel.onAction(RemoteAction.Retry)
                assertEquals(RemoteShutterUiState.Idle, awaitItem())

                // Attempt 2 — write fails again
                fakeBle.armWriteResult(Result.failure(RuntimeException("busy")))
                viewModel.onAction(RemoteAction.FullPress)
                advanceUntilIdle()
                assertEquals(RemoteShutterUiState.FullPress, awaitItem())
                val error2 = awaitItem()
                assertTrue(
                    "Expected Error after attempt 2, got $error2",
                    error2 is RemoteShutterUiState.Error,
                )

                // Retry → Idle
                viewModel.onAction(RemoteAction.Retry)
                assertEquals(RemoteShutterUiState.Idle, awaitItem())

                // Attempt 3 — explicitly re-arm success. FakeFujiBleClient.armWriteResult is
                // STICKY (it persists across writes, it is not one-shot), so the attempt-2
                // failure stays armed until we reset it here.
                fakeBle.armWriteResult(Result.success(Unit))
                viewModel.onAction(RemoteAction.FullPress)
                advanceUntilIdle()
                assertEquals(RemoteShutterUiState.FullPress, awaitItem())
                assertEquals(RemoteShutterUiState.CaptureComplete, awaitItem())

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── Retry action ──────────────────────────────────────────────────────────

    /**
     * Given: ViewModel is in Error state (write failure).
     * When:  user triggers Retry.
     * Then:  uiState resets to Idle; no new BLE write is issued.
     */
    @Test
    fun retryFromErrorStateResetsToIdleWithoutNewWrite() =
        runTest(testDispatcher) {
            fakeBle.setConnectionState(BleConnectionState.Connected)
            fakeBle.armWriteResult(Result.failure(RuntimeException("write-failed")))

            // Drive to error state
            viewModel.onAction(RemoteAction.FullPress)
            advanceUntilIdle()
            assertTrue(
                "Precondition: expected Error state, got ${viewModel.uiState.value}",
                viewModel.uiState.value is RemoteShutterUiState.Error,
            )
            val writeCallsBeforeRetry = fakeBle.writeCalls.size

            viewModel.uiState.test {
                val stateBefore = awaitItem()
                assertTrue(stateBefore is RemoteShutterUiState.Error)

                viewModel.onAction(RemoteAction.Retry)
                // Retry is synchronous (sets _uiState.value directly; no coroutine)
                assertEquals(RemoteShutterUiState.Idle, awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            // Retry must not issue any new BLE write
            assertEquals(writeCallsBeforeRetry, fakeBle.writeCalls.size)
        }

    /**
     * Given: ViewModel is in Idle state.
     * When:  user triggers Retry.
     * Then:  uiState remains Idle; no write is attempted.
     */
    @Test
    fun retryFromIdleStateIsNoOp() =
        runTest(testDispatcher) {
            viewModel.onAction(RemoteAction.Retry)
            advanceUntilIdle()

            assertEquals(RemoteShutterUiState.Idle, viewModel.uiState.value)
            assertTrue(fakeBle.writeCalls.isEmpty())
        }

    // ── BLE write payload verification ────────────────────────────────────────

    /**
     * Given: BLE is Connected.
     * When:  user triggers HalfPress, FullPress, Release in order.
     * Then:  exactly three writes are recorded with the correct 2-byte LE payloads.
     *
     * Payload values (master-constants.md §5, ble-wifi-discovery.md §ShootingRequest):
     *   HalfPress → S1 = u16 LE 0x0001 = [0x01, 0x00]
     *   FullPress  → S2 = u16 LE 0x0002 = [0x02, 0x00]
     *   Release    → S0 = u16 LE 0x0000 = [0x00, 0x00]
     */
    @Test
    fun halfFullReleaseActionsWriteCorrectBlePayloadsInOrder() =
        runTest(testDispatcher) {
            fakeBle.setConnectionState(BleConnectionState.Connected)

            viewModel.onAction(RemoteAction.HalfPress)
            advanceUntilIdle()
            viewModel.onAction(RemoteAction.FullPress)
            advanceUntilIdle()
            viewModel.onAction(RemoteAction.Release)
            advanceUntilIdle()

            assertEquals("Expected 3 BLE writes", 3, fakeBle.writeCalls.size)

            // HalfPress → S1 = [0x01, 0x00]
            val halfPayload = fakeBle.writeCalls[0].second
            assertEquals(2, halfPayload.size)
            assertEquals(0x01.toByte(), halfPayload[0])
            assertEquals(0x00.toByte(), halfPayload[1])

            // FullPress → S2 = [0x02, 0x00]
            val fullPayload = fakeBle.writeCalls[1].second
            assertEquals(2, fullPayload.size)
            assertEquals(0x02.toByte(), fullPayload[0])
            assertEquals(0x00.toByte(), fullPayload[1])

            // Release → S0 = [0x00, 0x00]
            val releasePayload = fakeBle.writeCalls[2].second
            assertEquals(2, releasePayload.size)
            assertEquals(0x00.toByte(), releasePayload[0])
            assertEquals(0x00.toByte(), releasePayload[1])
        }

    // ── Camera-model stub (capability gate passes for X-T5) ───────────────────

    /**
     * Given: BLE is Connected.
     * When:  user triggers HalfPress.
     * Then:  a BLE write is issued — confirming the ViewModel's "X-T5" stub
     *        passes the AllowlistRemoteCapabilityChecker gate.
     *
     * If the gate had rejected the model, no write would be attempted.
     */
    @Test
    fun viewModelPassesXt5CameraModelSoCapabilityGatePasses() =
        runTest(testDispatcher) {
            fakeBle.setConnectionState(BleConnectionState.Connected)

            viewModel.onAction(RemoteAction.HalfPress)
            advanceUntilIdle()

            assertTrue(
                "Expected at least one BLE write (capability gate passed for X-T5 stub)",
                fakeBle.writeCalls.isNotEmpty(),
            )
        }
}
