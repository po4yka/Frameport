package dev.po4yka.frameport.camera.bluetooth

import dev.po4yka.frameport.camera.api.BleCameraRef
import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.CharacteristicId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kable-backed implementation of [GattTransport].
 *
 * [AndroidFujiBleClient] still owns serialization, retries, and Frameport timeout policy.
 * This class adapts Kable Peripheral operations to Frameport's transport seam.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
internal class AndroidGattTransport
    @Inject
    constructor(
        private val advertisementCache: KableAdvertisementCache,
        private val peripheralFactory: KablePeripheralFactory,
    ) : GattTransport {
        private val _connectionState = MutableStateFlow(BleConnectionState.Disconnected)

        override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

        @Volatile
        private var peripheral: KablePeripheralAdapter? = null

        private val activePeripheralFlow = MutableStateFlow<KablePeripheralAdapter?>(null)

        override suspend fun connect(camera: BleCameraRef) {
            val advertisement =
                advertisementCache.advertisementFor(camera.id)

            closePeripheral()
            val nextPeripheral =
                if (advertisement != null) {
                    peripheralFactory.create(advertisement)
                } else {
                    peripheralFactory.create(camera.id)
                }
            peripheral = nextPeripheral
            _connectionState.value = BleConnectionState.Connecting
            runCatching {
                nextPeripheral.connect()
            }.onFailure { e ->
                closePeripheral()
                _connectionState.value = BleConnectionState.Disconnected
                throw e.asBleConnectFailure()
            }
            if (!nextPeripheral.isConnected) {
                closePeripheral()
                _connectionState.value = BleConnectionState.Disconnected
                throw BleTransportException.GattConnectionFailed()
            }
            activePeripheralFlow.value = nextPeripheral
            _connectionState.value = BleConnectionState.Connected
        }

        override suspend fun discoverServices() {
            Timber.d("BLE: services discovered count=${activePeripheral().discoveredServiceCount()}")
        }

        override suspend fun requestMtu(mtu: Int): Int =
            activePeripheral().maximumWriteValueLengthWithResponse() + ATT_MTU_HEADER_SIZE

        override suspend fun readCharacteristic(characteristicId: CharacteristicId): ByteArray =
            activePeripheral().read(characteristicId)

        override suspend fun writeCharacteristic(
            characteristicId: CharacteristicId,
            payload: ByteArray,
        ) {
            activePeripheral().writeWithResponse(
                characteristicId = characteristicId,
                payload = payload,
            )
        }

        override suspend fun disconnect() {
            closePeripheral()
            _connectionState.value = BleConnectionState.Disconnected
            Timber.d("BLE: Kable peripheral disconnected and closed")
        }

        override fun notificationFlow(characteristicId: CharacteristicId): Flow<ByteArray> =
            activePeripheralFlow
                .flatMapLatest { currentPeripheral ->
                    currentPeripheral?.observe(characteristicId) ?: emptyFlow()
                }

        private fun activePeripheral(): KablePeripheralAdapter =
            requireNotNull(peripheral) { "BLE peripheral is not connected" }

        private suspend fun closePeripheral() {
            peripheral?.runCatching {
                disconnect()
            }
            peripheral?.runCatching {
                close()
            }
            peripheral = null
            activePeripheralFlow.value = null
        }

        private companion object {
            const val ATT_MTU_HEADER_SIZE = 3
        }
    }
