package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.Advertisement
import dev.po4yka.frameport.camera.api.BleCameraRef
import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.CharacteristicId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGattTransportSavedIdReconnectTest {
    @Test
    fun connectUsesSavedCameraIdWhenAdvertisementCacheMisses() =
        runTest {
            val factory = RecordingKablePeripheralFactory()
            val transport =
                AndroidGattTransport(
                    advertisementCache = KableAdvertisementCache(),
                    peripheralFactory = factory,
                )

            transport.connect(
                BleCameraRef(
                    id = "AA:BB:CC:DD:EE:FF",
                    displayName = "X-T5",
                ),
            )

            assertEquals(listOf("AA:BB:CC:DD:EE:FF"), factory.identifierCreates)
            assertEquals(0, factory.advertisementCreateCount)
            assertEquals(1, factory.lastPeripheral.connectCallCount)
            assertEquals(BleConnectionState.Connected, transport.connectionState.value)
        }

    @Test
    fun connectFailsAndResetsStateWhenPeripheralDoesNotReachConnectedState() =
        runTest {
            val factory = RecordingKablePeripheralFactory(connectReportsConnected = false)
            val transport =
                AndroidGattTransport(
                    advertisementCache = KableAdvertisementCache(),
                    peripheralFactory = factory,
                )

            val result =
                runCatching {
                    transport.connect(
                        BleCameraRef(
                            id = "AA:BB:CC:DD:EE:FF",
                            displayName = "X-T5",
                        ),
                    )
                }

            assertEquals(true, result.isFailure)
            assertEquals(BleTransportException.GattConnectionFailed::class, result.exceptionOrNull()!!::class)
            assertEquals(BleConnectionState.Disconnected, transport.connectionState.value)
            assertEquals(1, factory.lastPeripheral.connectCallCount)
            assertEquals(1, factory.lastPeripheral.disconnectCallCount)
            assertEquals(1, factory.lastPeripheral.closeCallCount)
        }

    @Test
    fun connectPermissionDeniedMapsToBluetoothConnectPermissionError() =
        runTest {
            val cause = SecurityException("missing connect permission")
            val factory = RecordingKablePeripheralFactory(connectFailure = cause)
            val transport =
                AndroidGattTransport(
                    advertisementCache = KableAdvertisementCache(),
                    peripheralFactory = factory,
                )

            val result =
                runCatching {
                    transport.connect(
                        BleCameraRef(
                            id = "AA:BB:CC:DD:EE:FF",
                            displayName = "X-T5",
                        ),
                    )
                }
            val failure = result.exceptionOrNull() as BleTransportException.PermissionDenied

            assertEquals("android.permission.BLUETOOTH_CONNECT", failure.permission)
            assertEquals(cause, failure.cause)
            assertEquals(BleConnectionState.Disconnected, transport.connectionState.value)
            assertEquals(1, factory.lastPeripheral.disconnectCallCount)
            assertEquals(1, factory.lastPeripheral.closeCallCount)
        }

    @Test
    fun connectBluetoothDisabledMapsToBluetoothDisabledError() =
        runTest {
            val cause = IllegalStateException("Bluetooth disabled")
            val factory = RecordingKablePeripheralFactory(connectFailure = cause)
            val transport =
                AndroidGattTransport(
                    advertisementCache = KableAdvertisementCache(),
                    peripheralFactory = factory,
                )

            val result =
                runCatching {
                    transport.connect(
                        BleCameraRef(
                            id = "AA:BB:CC:DD:EE:FF",
                            displayName = "X-T5",
                        ),
                    )
                }
            val failure = result.exceptionOrNull()

            assertEquals(BleTransportException.BluetoothDisabled::class, failure!!::class)
            assertEquals(cause, failure.cause)
            assertEquals(BleConnectionState.Disconnected, transport.connectionState.value)
            assertEquals(1, factory.lastPeripheral.disconnectCallCount)
            assertEquals(1, factory.lastPeripheral.closeCallCount)
        }

    /**
     * M-22: discoverServices() must throw [BleTransportException.RequiredServiceMissing] when a
     * required Fujifilm GATT service UUID is absent from the discovered service table.
     */
    @Test
    fun discoverServicesThrowsRequiredServiceMissingWhenFujiServiceAbsent() =
        runTest {
            // Peripheral returns a non-Fujifilm service only — missing both required UUIDs.
            val factory =
                RecordingKablePeripheralFactory(
                    serviceUuids = listOf("0000180a-0000-1000-8000-00805f9b34fb"),
                )
            val transport =
                AndroidGattTransport(
                    advertisementCache = KableAdvertisementCache(),
                    peripheralFactory = factory,
                )
            transport.connect(BleCameraRef(id = "AA:BB:CC:DD:EE:FF", displayName = "X-T5"))

            val result =
                runCatching {
                    transport.discoverServices()
                }

            assertTrue(result.isFailure)
            assertTrue(
                "Expected RequiredServiceMissing but got ${result.exceptionOrNull()?.javaClass?.simpleName}",
                result.exceptionOrNull() is BleTransportException.RequiredServiceMissing,
            )
        }

    @Test
    fun discoverServicesSucceedsWhenAllRequiredServicesPresent() =
        runTest {
            val factory = RecordingKablePeripheralFactory()
            val transport =
                AndroidGattTransport(
                    advertisementCache = KableAdvertisementCache(),
                    peripheralFactory = factory,
                )
            transport.connect(BleCameraRef(id = "AA:BB:CC:DD:EE:FF", displayName = "X-T5"))

            // Should not throw — all required UUIDs are present in the factory default.
            transport.discoverServices()
        }

    private class RecordingKablePeripheralFactory(
        private val connectReportsConnected: Boolean = true,
        private val connectFailure: Throwable? = null,
        private val serviceUuids: List<String>? =
            listOf(
                BleConstants.SERVICE_CAMERA_INFORMATION,
                BleConstants.SERVICE_CONNECTED_DEVICE_INFORMATION,
            ),
    ) : KablePeripheralFactory {
        val identifierCreates = mutableListOf<String>()
        var advertisementCreateCount = 0
        lateinit var lastPeripheral: RecordingKablePeripheralAdapter

        override fun create(advertisement: Advertisement): KablePeripheralAdapter {
            advertisementCreateCount++
            lastPeripheral = RecordingKablePeripheralAdapter(connectReportsConnected, connectFailure, serviceUuids)
            return lastPeripheral
        }

        override fun create(identifier: String): KablePeripheralAdapter {
            identifierCreates += identifier
            lastPeripheral = RecordingKablePeripheralAdapter(connectReportsConnected, connectFailure, serviceUuids)
            return lastPeripheral
        }
    }

    private class RecordingKablePeripheralAdapter(
        private val connectReportsConnected: Boolean,
        private val connectFailure: Throwable?,
        private val serviceUuids: List<String>? =
            listOf(
                BleConstants.SERVICE_CAMERA_INFORMATION,
                BleConstants.SERVICE_CONNECTED_DEVICE_INFORMATION,
            ),
    ) : KablePeripheralAdapter {
        override var isConnected: Boolean = false
            private set

        var connectCallCount = 0
        var disconnectCallCount = 0
        var closeCallCount = 0

        override suspend fun connect() {
            connectCallCount++
            connectFailure?.let { throw it }
            isConnected = connectReportsConnected
        }

        override suspend fun disconnect() {
            disconnectCallCount++
            isConnected = false
        }

        override fun close() {
            closeCallCount++
        }

        override fun discoveredServiceCount(): Int = serviceUuids?.size ?: 0

        override fun discoveredServiceUuids(): List<String>? = serviceUuids

        override suspend fun maximumWriteValueLengthWithResponse(): Int = BleConstants.PREFERRED_MTU - 3

        override suspend fun read(characteristicId: CharacteristicId): ByteArray = ByteArray(0)

        override suspend fun writeWithResponse(
            characteristicId: CharacteristicId,
            payload: ByteArray,
        ) = Unit

        override fun observe(characteristicId: CharacteristicId): Flow<ByteArray> = emptyFlow()
    }
}
