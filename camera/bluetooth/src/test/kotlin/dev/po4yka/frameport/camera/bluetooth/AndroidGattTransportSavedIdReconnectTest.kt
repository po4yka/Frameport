package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.Advertisement
import com.juul.kable.Characteristic
import com.juul.kable.DiscoveredService
import com.juul.kable.PeripheralBuilder
import com.juul.kable.State
import com.juul.kable.WriteType
import dev.po4yka.frameport.camera.api.BleCameraRef
import dev.po4yka.frameport.camera.api.BleConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidGattTransportSavedIdReconnectTest {
    @Test
    fun connectUsesSavedCameraIdWhenAdvertisementCacheMisses() =
        runTest {
            val factory = RecordingKablePeripheralFactory(this)
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

    private class RecordingKablePeripheralFactory(
        private val scope: CoroutineScope,
    ) : KablePeripheralFactory {
        val identifierCreates = mutableListOf<String>()
        var advertisementCreateCount = 0
        lateinit var lastPeripheral: RecordingKablePeripheralAdapter

        override fun create(
            advertisement: Advertisement,
            configure: (PeripheralBuilder) -> Unit,
        ): KablePeripheralAdapter {
            advertisementCreateCount++
            lastPeripheral = RecordingKablePeripheralAdapter(scope)
            return lastPeripheral
        }

        override fun create(
            identifier: String,
            configure: (PeripheralBuilder) -> Unit,
        ): KablePeripheralAdapter {
            identifierCreates += identifier
            lastPeripheral = RecordingKablePeripheralAdapter(scope)
            return lastPeripheral
        }
    }

    private class RecordingKablePeripheralAdapter(
        private val scope: CoroutineScope,
    ) : KablePeripheralAdapter {
        private val _state = MutableStateFlow<State>(State.Disconnected())

        override val state: StateFlow<State> = _state
        override val services: StateFlow<List<DiscoveredService>?> = MutableStateFlow(emptyList())

        var connectCallCount = 0

        override suspend fun connect() {
            connectCallCount++
            _state.value = State.Connected(scope)
        }

        override suspend fun disconnect() {
            _state.value = State.Disconnected()
        }

        override fun close() = Unit

        override suspend fun maximumWriteValueLengthForType(writeType: WriteType): Int =
            BleConstants.PREFERRED_MTU - 3

        override suspend fun read(characteristic: Characteristic): ByteArray =
            ByteArray(0)

        override suspend fun write(
            characteristic: Characteristic,
            data: ByteArray,
            writeType: WriteType,
        ) = Unit

        override fun observe(characteristic: Characteristic): Flow<ByteArray> =
            emptyFlow()
    }
}
