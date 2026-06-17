package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.Characteristic
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.juul.kable.logs.Logging
import dev.po4yka.frameport.camera.api.BleCameraRef
import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.CharacteristicId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
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
    ) : GattTransport {
        private val _connectionState = MutableStateFlow(BleConnectionState.Disconnected)

        override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

        @Volatile
        private var peripheral: Peripheral? = null

        private val activePeripheralFlow = MutableStateFlow<Peripheral?>(null)
        private val explicitNotificationJobs = mutableMapOf<String, Job>()

        override suspend fun connect(camera: BleCameraRef) {
            val advertisement =
                advertisementCache.advertisementFor(camera.id)
                    ?: throw IllegalStateException("No BLE advertisement cached for selected camera")

            closePeripheral()
            val nextPeripheral =
                Peripheral(advertisement) {
                    autoConnectIf { false }
                    onServicesDiscovered {
                        val negotiatedMtu = requestMtu(BleConstants.PREFERRED_MTU)
                        Timber.d("BLE: Kable negotiated MTU=$negotiatedMtu")
                    }
                    logging {
                        identifier = KABLE_LOG_IDENTIFIER
                        engine = FrameportKableLogEngine
                        level = Logging.Level.Warnings
                        format = Logging.Format.Compact
                    }
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

        override suspend fun setNotification(
            characteristicId: CharacteristicId,
            enable: Boolean,
        ) {
            val currentPeripheral = activePeripheral()
            explicitNotificationJobs.remove(characteristicId.value)?.cancelAndJoin()
            if (!enable) return

            val characteristic = characteristicFor(currentPeripheral, characteristicId)
            val subscribed = CompletableDeferred<Unit>()
            val job =
                currentPeripheral.scope.launch {
                    try {
                        currentPeripheral
                            .observe(characteristic) {
                                subscribed.complete(Unit)
                            }.collect()
                    } catch (e: Exception) {
                        if (!subscribed.isCompleted) {
                            subscribed.completeExceptionally(e)
                        }
                        throw e
                    }
                }
            explicitNotificationJobs[characteristicId.value] = job
            try {
                subscribed.await()
            } catch (e: Exception) {
                explicitNotificationJobs.remove(characteristicId.value)
                job.cancel()
                throw e
            }
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

        private fun activePeripheral(): Peripheral =
            requireNotNull(peripheral) { "BLE peripheral is not connected" }

        private fun characteristicFor(
            currentPeripheral: Peripheral,
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
            explicitNotificationJobs.values.forEach { job ->
                job.cancelAndJoin()
            }
            explicitNotificationJobs.clear()
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
