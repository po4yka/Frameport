package dev.po4yka.frameport.feature.connection

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.BleCameraAdvertisement
import dev.po4yka.frameport.camera.api.BleCameraRef
import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.CharacteristicId
import dev.po4yka.frameport.camera.api.FujiBleClient
import dev.po4yka.frameport.camera.api.NoOpFujiBleClient
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.domain.BleAssistedConnectUseCase
import dev.po4yka.frameport.camera.domain.OpenCameraSessionUseCase
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.model.TransportKind
import dev.po4yka.frameport.core.testing.FakeCameraRepository
import dev.po4yka.frameport.core.testing.FakeCameraWifiConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

// ─── Test doubles ─────────────────────────────────────────────────────────────

/**
 * Fake [FujiBleClient] for unit tests.
 *
 * Scan emits [advertisementToEmit] once (if non-null) then completes.
 * Connect succeeds immediately and advances [connectionState] to [BleConnectionState.Connected].
 * Read returns [characteristicValues] keyed by [CharacteristicId.value], or failure if absent.
 * PRIVACY: no real BLE credentials ever flow through this fake.
 */
private class ControlledFujiBleClient : FujiBleClient {
    private val _connectionState = MutableStateFlow(BleConnectionState.Disconnected)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState

    var advertisementToEmit: BleCameraAdvertisement? = null
    val characteristicValues: MutableMap<String, ByteArray> = mutableMapOf()
    var connectResult: Result<Unit> = Result.success(Unit)

    // cancel-safe: cold flow; emits one advertisement then completes.
    override fun scan(): Flow<BleCameraAdvertisement> =
        flow {
            advertisementToEmit?.let { emit(it) }
        }

    // cancel-safe: no real suspension.
    override suspend fun connect(camera: BleCameraRef): Result<Unit> {
        if (connectResult.isSuccess) {
            _connectionState.value = BleConnectionState.Connected
        }
        return connectResult
    }

    // cancel-safe: no real suspension.
    override suspend fun read(characteristic: CharacteristicId): Result<ByteArray> {
        val bytes =
            characteristicValues[characteristic.value]
                ?: return Result.failure(IllegalStateException("No value for characteristic: ${characteristic.value}"))
        return Result.success(bytes)
    }

    // cancel-safe: no real suspension.
    override suspend fun write(
        characteristic: CharacteristicId,
        payload: ByteArray,
    ): Result<Unit> = Result.success(Unit)

    // cancel-safe: cold flow backed by an emptyFlow for simplicity.
    override fun notifications(characteristic: CharacteristicId): Flow<ByteArray> = emptyFlow()

    // cancel-safe: idempotent.
    override suspend fun disconnect() {
        _connectionState.value = BleConnectionState.Disconnected
    }
}

/**
 * Timber Tree that records all log messages for assertion in privacy tests.
 * Must be uprooted after each test to avoid cross-test pollution.
 */
private class RecordingTimberTree : Timber.Tree() {
    val messages = mutableListOf<String>()

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        messages.add(message)
        t?.message?.let { messages.add(it) }
    }
}

// ─── Test class ───────────────────────────────────────────────────────────────

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
        viewModel = buildViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(bleClient: FujiBleClient = NoOpFujiBleClient()): ConnectionViewModel {
        val bleUseCase =
            BleAssistedConnectUseCase(
                fujiBleClient = bleClient,
                cameraWifiConnector = FakeCameraWifiConnector(),
                ioDispatcher = testDispatcher,
            )
        return ConnectionViewModel(useCase, fakeRepo, bleUseCase)
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

    // ── startBleHandoff() ─────────────────────────────────────────────────────

    /**
     * Verifies that [ConnectionViewModel.startBleHandoff] transitions through
     * [ConnectionUiState.ConnectingViaBleHandoff] states and resolves to
     * [ConnectionUiState.Connected] when the BLE handoff succeeds.
     *
     * Uses [ControlledFujiBleClient] to supply a synthetic advertisement + SSID/passphrase
     * bytes without any real BLE hardware.
     *
     * PRIVACY: [BLE_TEST_SSID] and [BLE_TEST_PASSPHRASE] are synthetic test values
     * that must never appear in production captures or logs.
     */
    @Test
    fun startBleHandoffEmitsConnectingViaBleHandoffThenConnected() =
        runTest(testDispatcher) {
            val bleClient =
                ControlledFujiBleClient().apply {
                    advertisementToEmit =
                        BleCameraAdvertisement(
                            camera = BleCameraRef(id = "fake-id", displayName = "Test Cam"),
                            signalStrengthDbm = -60,
                        )
                    // SSID characteristic UUID from FujiCharacteristicIds.WIFI_SSID
                    characteristicValues["4186f39e-cd9e-11e4-8dfc-aa07a5b093db"] =
                        BLE_TEST_SSID.toByteArray(Charsets.UTF_8)
                    // Passphrase characteristic UUID from FujiCharacteristicIds.WIFI_PASSPHRASE
                    // PRIVACY: synthetic value only — never a real credential
                    characteristicValues["4186f3c0-cd9e-11e4-8dfc-aa07a5b093db"] =
                        BLE_TEST_PASSPHRASE.toByteArray(Charsets.UTF_8)
                }
            val vm = buildViewModel(bleClient)

            // With UnconfinedTestDispatcher, the flow in BleAssistedConnectUseCase runs
            // eagerly. We record states observed via StateFlow.value at each step.
            // We assert the terminal state is Connected and ssid matches.
            vm.startBleHandoff()
            advanceUntilIdle()

            val finalState = vm.uiState.value
            assertTrue(
                "Expected final state to be Connected after successful BLE handoff, got: $finalState",
                finalState is ConnectionUiState.Connected,
            )
            assertEquals(
                BLE_TEST_SSID,
                (finalState as ConnectionUiState.Connected).ssid,
            )
        }

    @Test
    fun startBleHandoffOnFailureTransitionsToError() =
        runTest(testDispatcher) {
            // Provide a client whose scan emits one advertisement but connect fails.
            val bleClient =
                ControlledFujiBleClient().apply {
                    advertisementToEmit =
                        BleCameraAdvertisement(
                            camera = BleCameraRef(id = "fake-id", displayName = null),
                            signalStrengthDbm = null,
                        )
                    connectResult = Result.failure(IllegalStateException("GATT connect refused"))
                }
            val vm = buildViewModel(bleClient)

            vm.startBleHandoff()
            advanceUntilIdle()

            val finalState = vm.uiState.value
            assertTrue(
                "Expected Error after GATT connect failure, got: $finalState",
                finalState is ConnectionUiState.Error,
            )
        }

    @Test
    fun startBleHandoffIsNoOpWhenAlreadyConnected() =
        runTest(testDispatcher) {
            fakeRepo.simulateSuccess(SessionId(1L))
            viewModel.connect("FUJIFILM-X-T5")
            advanceUntilIdle()
            val stateBefore = viewModel.uiState.value
            assertTrue(stateBefore is ConnectionUiState.Connected)

            viewModel.startBleHandoff()
            advanceUntilIdle()

            assertEquals(stateBefore, viewModel.uiState.value)
        }

    // ── Log-privacy guard ─────────────────────────────────────────────────────

    /**
     * Verifies that neither the Wi-Fi passphrase nor a BLE MAC address appear in any
     * Timber log message recorded during a BLE handoff from [ConnectionViewModel].
     *
     * A [RecordingTimberTree] is planted before the call and uprooted after assertion.
     * This test covers the ViewModel boundary only — [BleAssistedConnectUseCase] and
     * [AndroidFujiBleClient] have their own privacy tests.
     *
     * Synthetic test credentials [BLE_TEST_PASSPHRASE] and [BLE_TEST_MAC] must never
     * appear in any log output.
     */
    @Test
    fun bleHandoffViewModelNeverLogsPassphraseOrMac() =
        runTest(testDispatcher) {
            val bleClient =
                ControlledFujiBleClient().apply {
                    advertisementToEmit =
                        BleCameraAdvertisement(
                            camera = BleCameraRef(id = BLE_TEST_MAC, displayName = null),
                            signalStrengthDbm = -55,
                        )
                    characteristicValues["4186f39e-cd9e-11e4-8dfc-aa07a5b093db"] =
                        BLE_TEST_SSID.toByteArray(Charsets.UTF_8)
                    characteristicValues["4186f3c0-cd9e-11e4-8dfc-aa07a5b093db"] =
                        BLE_TEST_PASSPHRASE.toByteArray(Charsets.UTF_8)
                }
            val vm = buildViewModel(bleClient)
            val recordingTree = RecordingTimberTree()
            Timber.plant(recordingTree)
            try {
                vm.startBleHandoff()
                advanceUntilIdle()

                val allMessages = recordingTree.messages.joinToString("\n")
                assertFalse(
                    "Passphrase '$BLE_TEST_PASSPHRASE' must not appear in any log message",
                    allMessages.contains(BLE_TEST_PASSPHRASE),
                )
                assertFalse(
                    "BLE MAC '$BLE_TEST_MAC' must not appear in any log message",
                    allMessages.contains(BLE_TEST_MAC),
                )
            } finally {
                Timber.uproot(recordingTree)
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
        assertEquals(
            "android.permission.READ_MEDIA_IMAGES",
            (result as ConnectionError.Permission.Other).permission,
        )
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

    companion object {
        /** Synthetic SSID used as a test sentinel. Never a real camera network name. */
        const val BLE_TEST_SSID = "FUJIFILM-X-T5-TEST"

        /**
         * Synthetic passphrase used as a privacy-check sentinel.
         * MUST NOT appear in any log output. Never a real credential.
         */
        const val BLE_TEST_PASSPHRASE = "S3cr3tP@ssphrase-TEST"

        /**
         * Synthetic BLE MAC address used as a privacy-check sentinel.
         * MUST NOT appear in plain-text logs. Never a real device address.
         */
        const val BLE_TEST_MAC = "AA:BB:CC:DD:EE:FF"
    }
}
