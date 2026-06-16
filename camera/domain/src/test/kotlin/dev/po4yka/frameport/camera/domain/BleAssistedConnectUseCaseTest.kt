package dev.po4yka.frameport.camera.domain

import dev.po4yka.frameport.camera.api.BleCameraAdvertisement
import dev.po4yka.frameport.camera.api.BleCameraRef
import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.CharacteristicId
import dev.po4yka.frameport.camera.api.FujiBleClient
import dev.po4yka.frameport.core.testing.FakeCameraWifiConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleAssistedConnectUseCaseTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun bleHandoffRequestsCameraNetworkWithBssid() =
        runTest(dispatcher) {
            val wifiConnector = FakeCameraWifiConnector()
            val bleClient =
                FakeFujiBleClient(
                    values =
                        mapOf(
                            FujiCharacteristicIds.WIFI_SSID.value to "FUJIFILM-X-T5-TEST".toByteArray(),
                            FujiCharacteristicIds.WIFI_PASSPHRASE.value to "synthetic-passphrase".toByteArray(),
                            FujiCharacteristicIds.WIFI_BSSID.value to "AA:BB:CC:DD:EE:FF".toByteArray(),
                        ),
                )
            val useCase = BleAssistedConnectUseCase(bleClient, wifiConnector, dispatcher)

            val states = useCase().toList()

            assertTrue(states.last() is BleHandoffState.Connected)
            val credentials = wifiConnector.lastRequestedCredentials
            assertEquals("FUJIFILM-X-T5-TEST", credentials?.ssid)
            assertEquals("synthetic-passphrase", credentials?.passphrase)
            assertEquals("AA:BB:CC:DD:EE:FF", credentials?.bssid)
        }

    @Test
    fun bleHandoffFailsBeforeNetworkRequestWhenBssidIsMalformed() =
        runTest(dispatcher) {
            val wifiConnector = FakeCameraWifiConnector()
            val bleClient =
                FakeFujiBleClient(
                    values =
                        mapOf(
                            FujiCharacteristicIds.WIFI_SSID.value to "FUJIFILM-X-T5-TEST".toByteArray(),
                            FujiCharacteristicIds.WIFI_PASSPHRASE.value to "synthetic-passphrase".toByteArray(),
                            FujiCharacteristicIds.WIFI_BSSID.value to "not-a-mac".toByteArray(),
                        ),
                )
            val useCase = BleAssistedConnectUseCase(bleClient, wifiConnector, dispatcher)

            val states = useCase().toList()

            assertEquals(BleHandoffState.Failed("BSSID characteristic returned malformed value."), states.last())
            assertEquals(null, wifiConnector.lastRequestedCredentials)
        }
}

private class FakeFujiBleClient(
    private val values: Map<String, ByteArray>,
) : FujiBleClient {
    private val mutableConnectionState = MutableStateFlow(BleConnectionState.Disconnected)
    override val connectionState: StateFlow<BleConnectionState> = mutableConnectionState

    override fun scan(): Flow<BleCameraAdvertisement> =
        flow {
            emit(
                BleCameraAdvertisement(
                    camera = BleCameraRef(id = "fake-camera", displayName = "Test Camera"),
                    signalStrengthDbm = -50,
                ),
            )
        }

    override suspend fun connect(camera: BleCameraRef): Result<Unit> {
        mutableConnectionState.value = BleConnectionState.Connected
        return Result.success(Unit)
    }

    override suspend fun read(characteristic: CharacteristicId): Result<ByteArray> =
        values[characteristic.value]?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("missing characteristic"))

    override suspend fun write(
        characteristic: CharacteristicId,
        payload: ByteArray,
    ): Result<Unit> = Result.success(Unit)

    override fun notifications(characteristic: CharacteristicId): Flow<ByteArray> = emptyFlow()

    override suspend fun disconnect() {
        mutableConnectionState.value = BleConnectionState.Disconnected
    }
}
