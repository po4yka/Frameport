package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.Characteristic
import com.juul.kable.PeripheralBuilder
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.juul.kable.logs.Logging
import dev.po4yka.frameport.camera.api.BleCameraRef
import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.CharacteristicId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Kable-backed implementation of [GattTransport].
 *
 * [AndroidFujiBleClient] still owns serialization, retries, and Frameport timeout policy.
 * This class adapts Kable Peripheral operations to Frameport's transport seam.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
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
                    peripheralFactory.create(advertisement, ::configurePeripheral)
                } else {
                    peripheralFactory.create(camera.id, ::configurePeripheral)
                }
            peripheral = nextPeripheral
            _connectionState.value = BleConnectionState.Connecting
            nextPeripheral.connect()
            activePeripheralFlow.value = nextPeripheral
            _connectionState.value =
                when (nextPeripheral.state.value) {
                    is State.Connected -> BleConnectionState.Connected
                    else -> BleConnectionState.Connected
                }
        }

        override suspend fun discoverServices() {
            val services =
                activePeripheral()
                    .services
                    .value
                    ?: throw IllegalStateException("Kable connect completed without discovered services")
            Timber.d("BLE: services discovered count=${services.size}")
        }

        override suspend fun requestMtu(mtu: Int): Int =
            activePeripheral().maximumWriteValueLengthForType(WriteType.WithResponse) + ATT_MTU_HEADER_SIZE

        override suspend fun readCharacteristic(characteristicId: CharacteristicId): ByteArray =
            activePeripheral().let { currentPeripheral ->
                currentPeripheral.read(characteristicFor(currentPeripheral, characteristicId))
            }

        override suspend fun writeCharacteristic(
            characteristicId: CharacteristicId,
            payload: ByteArray,
        ) {
            val currentPeripheral = activePeripheral()
            currentPeripheral.write(
                characteristic = characteristicFor(currentPeripheral, characteristicId),
                data = payload,
                writeType = WriteType.WithResponse,
            )
        }

        override suspend fun disconnect() {
            closePeripheral()
            _connectionState.value = BleConnectionState.Disconnected
            Timber.d("BLE: Kable peripheral disconnected and closed")
        }

        override fun notificationFlow(characteristicId: CharacteristicId): Flow<ByteArray> =
            activePeripheralFlow
                .filterNotNull()
                .flatMapLatest { currentPeripheral ->
                    currentPeripheral.observe(characteristicFor(currentPeripheral, characteristicId))
                }

        private fun activePeripheral(): KablePeripheralAdapter =
            requireNotNull(peripheral) { "BLE peripheral is not connected" }

        private fun configurePeripheral(builder: PeripheralBuilder) {
            builder.autoConnectIf { false }
            builder.onServicesDiscovered {
                val negotiatedMtu = requestMtu(BleConstants.PREFERRED_MTU)
                Timber.d("BLE: Kable negotiated MTU=$negotiatedMtu")
            }
            builder.logging {
                identifier = KABLE_LOG_IDENTIFIER
                engine = FrameportKableLogEngine
                level = Logging.Level.Warnings
                format = Logging.Format.Compact
            }
        }

        private fun characteristicFor(
            currentPeripheral: KablePeripheralAdapter,
            characteristicId: CharacteristicId,
        ): Characteristic {
            val characteristicUuid = Uuid.parse(characteristicId.value)
            val serviceUuid =
                currentPeripheral
                    .services
                    .value
                    ?.firstOrNull { service ->
                        service.characteristics.any { it.characteristicUuid == characteristicUuid }
                    }?.serviceUuid
                    ?: throw IllegalStateException(
                        "No service found for characteristic ${characteristicId.value}",
                    )
            return characteristicOf(serviceUuid, characteristicUuid)
        }

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
            const val KABLE_LOG_IDENTIFIER = "Frameport BLE"
            const val ATT_MTU_HEADER_SIZE = 3
        }
    }
