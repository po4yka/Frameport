package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.Advertisement
import dev.po4yka.frameport.camera.api.BleCameraRef
import dev.po4yka.frameport.camera.api.CharacteristicId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidGattTransportNotificationLifecycleTest {
    private val characteristicId = CharacteristicId(BleConstants.CHR_CAMERA_VITAL_STATE)

    @Test
    fun notificationCollectorBeforeConnectWaitsUntilPeripheralIsConnected() =
        runTest {
            val factory = RecordingKablePeripheralFactory()
            val transport = transport(factory)

            val collector =
                launch {
                    transport.notificationFlow(characteristicId).collect {}
                }
            advanceUntilIdle()

            assertTrue(factory.createdAdapters.isEmpty())

            transport.connect(camera("AA:BB:CC:DD:EE:FF"))
            advanceUntilIdle()

            val adapter = factory.createdAdapters.single()
            assertEquals(listOf(characteristicId), adapter.observeCalls)
            assertEquals(1, adapter.activeObservationCount)

            collector.cancelAndJoin()
        }

    @Test
    fun disconnectCancelsActiveNotificationObservationButCollectorCanSurviveForReconnect() =
        runTest {
            val factory = RecordingKablePeripheralFactory()
            val transport = transport(factory)
            val collector =
                launch {
                    transport.notificationFlow(characteristicId).collect {}
                }

            transport.connect(camera("AA:BB:CC:DD:EE:FF"))
            advanceUntilIdle()
            val firstAdapter = factory.createdAdapters.single()
            assertEquals(1, firstAdapter.activeObservationCount)

            transport.disconnect()
            advanceUntilIdle()

            assertEquals(0, firstAdapter.activeObservationCount)
            assertEquals(1, firstAdapter.cancelledObservationCount)
            assertTrue(collector.isActive)

            transport.connect(camera("AA:BB:CC:DD:EE:FF"))
            advanceUntilIdle()

            val secondAdapter = factory.createdAdapters.last()
            assertEquals(1, secondAdapter.activeObservationCount)
            assertEquals(listOf(characteristicId), secondAdapter.observeCalls)

            collector.cancelAndJoin()
        }

    @Test
    fun reconnectSwitchesNotificationObservationToNewestPeripheral() =
        runTest {
            val factory = RecordingKablePeripheralFactory()
            val transport = transport(factory)
            val collector =
                launch {
                    transport.notificationFlow(characteristicId).collect {}
                }

            transport.connect(camera("AA:BB:CC:DD:EE:FF"))
            advanceUntilIdle()
            val firstAdapter = factory.createdAdapters.single()
            assertEquals(1, firstAdapter.activeObservationCount)

            transport.connect(camera("11:22:33:44:55:66"))
            advanceUntilIdle()

            val secondAdapter = factory.createdAdapters.last()
            assertEquals(0, firstAdapter.activeObservationCount)
            assertEquals(1, firstAdapter.cancelledObservationCount)
            assertEquals(1, secondAdapter.activeObservationCount)
            assertEquals(listOf(characteristicId), secondAdapter.observeCalls)

            collector.cancelAndJoin()
        }

    @Test
    fun eachNotificationCollectorCreatesIndependentObservation() =
        runTest {
            val factory = RecordingKablePeripheralFactory()
            val transport = transport(factory)
            val firstCollector =
                launch {
                    transport.notificationFlow(characteristicId).collect {}
                }
            val secondCollector =
                launch {
                    transport.notificationFlow(characteristicId).collect {}
                }

            transport.connect(camera("AA:BB:CC:DD:EE:FF"))
            advanceUntilIdle()

            val adapter = factory.createdAdapters.single()
            assertEquals(listOf(characteristicId, characteristicId), adapter.observeCalls)
            assertEquals(2, adapter.activeObservationCount)

            firstCollector.cancelAndJoin()
            secondCollector.cancelAndJoin()
            assertEquals(0, adapter.activeObservationCount)
            assertEquals(2, adapter.cancelledObservationCount)
        }

    private fun transport(factory: RecordingKablePeripheralFactory): AndroidGattTransport =
        AndroidGattTransport(
            advertisementCache = KableAdvertisementCache(),
            peripheralFactory = factory,
        )

    private fun camera(id: String): BleCameraRef =
        BleCameraRef(
            id = id,
            displayName = "X-T5",
        )

    private class RecordingKablePeripheralFactory : KablePeripheralFactory {
        val createdAdapters = mutableListOf<RecordingKablePeripheralAdapter>()

        override fun create(advertisement: Advertisement): KablePeripheralAdapter =
            createAdapter()

        override fun create(identifier: String): KablePeripheralAdapter =
            createAdapter()

        private fun createAdapter(): RecordingKablePeripheralAdapter =
            RecordingKablePeripheralAdapter()
                .also(createdAdapters::add)
    }

    private class RecordingKablePeripheralAdapter : KablePeripheralAdapter {
        override var isConnected: Boolean = false
            private set

        val observeCalls = mutableListOf<CharacteristicId>()
        var activeObservationCount = 0
            private set
        var cancelledObservationCount = 0
            private set

        override suspend fun connect() {
            isConnected = true
        }

        override suspend fun disconnect() {
            isConnected = false
        }

        override fun close() = Unit

        override fun discoveredServiceCount(): Int = 0

        override suspend fun maximumWriteValueLengthWithResponse(): Int =
            BleConstants.PREFERRED_MTU - 3

        override suspend fun read(characteristicId: CharacteristicId): ByteArray =
            ByteArray(0)

        override suspend fun writeWithResponse(
            characteristicId: CharacteristicId,
            payload: ByteArray,
        ) = Unit

        override fun observe(characteristicId: CharacteristicId): Flow<ByteArray> =
            callbackFlow {
                observeCalls += characteristicId
                activeObservationCount++
                awaitClose {
                    activeObservationCount--
                    cancelledObservationCount++
                }
            }
    }
}
