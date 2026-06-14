package dev.po4yka.frameport.feature.connection

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.domain.OpenCameraSessionUseCase
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.model.TransportKind
import dev.po4yka.frameport.core.testing.FakeCameraRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepo: FakeCameraRepository
    private lateinit var useCase: OpenCameraSessionUseCase
    private lateinit var viewModel: ConnectionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeCameraRepository()
        // UnconfinedTestDispatcher so the flowOn(ioDispatcher) in OpenCameraSessionUseCase
        // executes eagerly in the test coroutine context without needing advanceUntilIdle.
        useCase = OpenCameraSessionUseCase(fakeRepo, testDispatcher)
        viewModel = ConnectionViewModel(useCase, fakeRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun initialStateIsIdle() {
        assertTrue(viewModel.uiState.value is ConnectionUiState.Idle)
    }

    // ── SSID input ────────────────────────────────────────────────────────────

    @Test
    fun onSsidChangedWithTextTransitionsToEnteringCredentials() {
        viewModel.onSsidChanged("FUJIFILM-X-T5")
        val state = viewModel.uiState.value
        assertTrue("expected EnteringCredentials, got $state", state is ConnectionUiState.EnteringCredentials)
        assertEquals("FUJIFILM-X-T5", (state as ConnectionUiState.EnteringCredentials).ssid)
    }

    @Test
    fun onSsidChangedWithBlankTextResetsToIdle() {
        viewModel.onSsidChanged("FUJIFILM-X-T5")
        viewModel.onSsidChanged("   ")
        assertTrue(viewModel.uiState.value is ConnectionUiState.Idle)
    }

    // ── connect() — happy path ────────────────────────────────────────────────

    @Test
    fun connectWithSuccessTransitionsToConnected() =
        runTest(testDispatcher) {
            val expectedId = SessionId(7L)
            fakeRepo.simulateSuccess(expectedId)

            viewModel.connect("FUJIFILM-X-T5")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("expected Connected, got $state", state is ConnectionUiState.Connected)
            val connected = state as ConnectionUiState.Connected
            assertEquals(expectedId, connected.sessionId)
            assertEquals("FUJIFILM-X-T5", connected.ssid)
        }

    // ── connect() — error paths ───────────────────────────────────────────────

    @Test
    fun connectWithWifiUnavailableErrorMapsToConnectionErrorWifiNetworkUnavailable() =
        runTest(testDispatcher) {
            fakeRepo.simulateFailure(
                FrameportError.TransportUnavailable(
                    transportKind = TransportKind.WifiPtpIp,
                    message = "network unavailable",
                ),
            )

            viewModel.connect("FUJIFILM-X-T5")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("expected Error, got $state", state is ConnectionUiState.Error)
            val error = state as ConnectionUiState.Error
            assertEquals(ConnectionError.Wifi.NetworkUnavailable, error.connectionError)
            assertEquals("FUJIFILM-X-T5", error.ssid)
        }

    @Test
    fun connectWithProtocolUnavailableErrorMapsToHandshakeRejected() =
        runTest(testDispatcher) {
            fakeRepo.simulateFailure(
                FrameportError.ProtocolUnavailable(message = "handshake failed"),
            )

            viewModel.connect("FUJIFILM-X-T5")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("expected Error, got $state", state is ConnectionUiState.Error)
            assertEquals(
                ConnectionError.Protocol.HandshakeRejected,
                (state as ConnectionUiState.Error).connectionError,
            )
        }

    @Test
    fun connectWithPermissionDeniedErrorMapsToBluetoothScan() =
        runTest(testDispatcher) {
            fakeRepo.simulateFailure(
                FrameportError.PermissionDenied(
                    permission = "android.permission.BLUETOOTH_SCAN",
                    message = "permission denied",
                ),
            )

            viewModel.connect("FUJIFILM-X-T5")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("expected Error, got $state", state is ConnectionUiState.Error)
            assertEquals(
                ConnectionError.Permission.BluetoothScan,
                (state as ConnectionUiState.Error).connectionError,
            )
        }

    @Test
    fun connectWithUnknownErrorMapsToConnectionErrorUnknown() =
        runTest(testDispatcher) {
            fakeRepo.simulateFailure(FrameportError.Unknown(message = "something went wrong"))

            viewModel.connect("FUJIFILM-X-T5")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("expected Error, got $state", state is ConnectionUiState.Error)
            assertTrue((state as ConnectionUiState.Error).connectionError is ConnectionError.Unknown)
        }

    @Test
    fun connectWithBlankSsidDoesNothing() =
        runTest(testDispatcher) {
            viewModel.connect("   ")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is ConnectionUiState.Idle)
        }

    // ── retry() ───────────────────────────────────────────────────────────────

    @Test
    fun retryFromErrorStateRetriesConnectionWithSameSsid() =
        runTest(testDispatcher) {
            // First attempt fails.
            fakeRepo.simulateFailure(FrameportError.Unknown("first failure"))
            viewModel.connect("FUJIFILM-X-T5")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is ConnectionUiState.Error)

            // Second attempt succeeds.
            val expectedId = SessionId(99L)
            fakeRepo.simulateSuccess(expectedId)
            viewModel.retry()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("expected Connected after retry, got $state", state is ConnectionUiState.Connected)
            assertEquals(expectedId, (state as ConnectionUiState.Connected).sessionId)
        }

    @Test
    fun retryFromNonErrorStateIsNoOp() =
        runTest(testDispatcher) {
            // VM is Idle — retry must do nothing.
            viewModel.retry()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is ConnectionUiState.Idle)
        }

    // ── disconnect() ──────────────────────────────────────────────────────────

    @Test
    fun disconnectFromConnectedStateClosesSessionAndResetsToIdle() =
        runTest(testDispatcher) {
            val sessionId = SessionId(3L)
            fakeRepo.simulateSuccess(sessionId)
            viewModel.connect("FUJIFILM-X-T5")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is ConnectionUiState.Connected)

            viewModel.disconnect()
            advanceUntilIdle()

            assertEquals(listOf(sessionId), fakeRepo.closedSessions)
            assertTrue(viewModel.uiState.value is ConnectionUiState.Idle)
        }

    @Test
    fun disconnectFromNonConnectedStateIsNoOp() =
        runTest(testDispatcher) {
            viewModel.disconnect()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is ConnectionUiState.Idle)
            assertTrue(fakeRepo.closedSessions.isEmpty())
        }

    // ── cancel() ──────────────────────────────────────────────────────────────

    @Test
    fun cancelFromIdleEmitsCancelledNavEvent() =
        runTest(testDispatcher) {
            viewModel.navEvents.test {
                viewModel.cancel()
                advanceUntilIdle()
                assertEquals(ConnectionNavEvent.Cancelled, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun cancelFromConnectedStateClosesSessionBeforeEmittingNavEvent() =
        runTest(testDispatcher) {
            val sessionId = SessionId(5L)
            fakeRepo.simulateSuccess(sessionId)
            viewModel.connect("FUJIFILM-X-T5")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is ConnectionUiState.Connected)

            viewModel.navEvents.test {
                viewModel.cancel()
                advanceUntilIdle()
                assertEquals(listOf(sessionId), fakeRepo.closedSessions)
                assertEquals(ConnectionNavEvent.Cancelled, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── mapToConnectionError() unit tests (pure function) ─────────────────────

    @Test
    fun mapToConnectionErrorMapsPermissionDeniedBluetoothScan() {
        val result =
            mapToConnectionError(
                FrameportError.PermissionDenied("android.permission.BLUETOOTH_SCAN", "denied"),
            )
        assertEquals(ConnectionError.Permission.BluetoothScan, result)
    }

    @Test
    fun mapToConnectionErrorMapsPermissionDeniedBluetoothConnect() {
        val result =
            mapToConnectionError(
                FrameportError.PermissionDenied("android.permission.BLUETOOTH_CONNECT", "denied"),
            )
        assertEquals(ConnectionError.Permission.BluetoothConnect, result)
    }

    @Test
    fun mapToConnectionErrorMapsPermissionDeniedNearbyWifiDevices() {
        val result =
            mapToConnectionError(
                FrameportError.PermissionDenied("android.permission.NEARBY_WIFI_DEVICES", "denied"),
            )
        assertEquals(ConnectionError.Permission.NearbyWifiDevices, result)
    }

    @Test
    fun mapToConnectionErrorMapsPermissionDeniedOther() {
        val result =
            mapToConnectionError(
                FrameportError.PermissionDenied("android.permission.READ_MEDIA_IMAGES", "denied"),
            )
        assertTrue(result is ConnectionError.Permission.Other)
        assertEquals("android.permission.READ_MEDIA_IMAGES", (result as ConnectionError.Permission.Other).permission)
    }

    @Test
    fun mapToConnectionErrorMapsTransportUnavailableWifiPtpIp() {
        val result =
            mapToConnectionError(
                FrameportError.TransportUnavailable(TransportKind.WifiPtpIp, "no network"),
            )
        assertEquals(ConnectionError.Wifi.NetworkUnavailable, result)
    }

    @Test
    fun mapToConnectionErrorMapsTransportUnavailableBle() {
        val result =
            mapToConnectionError(
                FrameportError.TransportUnavailable(TransportKind.BluetoothLe, "ble error"),
            )
        assertTrue(result is ConnectionError.Unknown)
    }

    @Test
    fun mapToConnectionErrorMapsProtocolUnavailableToHandshakeRejected() {
        val result = mapToConnectionError(FrameportError.ProtocolUnavailable("handshake failed"))
        assertEquals(ConnectionError.Protocol.HandshakeRejected, result)
    }

    @Test
    fun mapToConnectionErrorMapsMediaUnavailableToUnknown() {
        val result = mapToConnectionError(FrameportError.MediaUnavailable(null, "no media"))
        assertTrue(result is ConnectionError.Unknown)
    }

    @Test
    fun mapToConnectionErrorMapsUnknownToUnknown() {
        val result = mapToConnectionError(FrameportError.Unknown("something"))
        assertTrue(result is ConnectionError.Unknown)
        assertEquals("something", (result as ConnectionError.Unknown).message)
    }
}
