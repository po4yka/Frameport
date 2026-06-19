package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.Advertisement
import com.juul.kable.DiscoveredService
import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.logs.Logging
import dev.po4yka.frameport.camera.api.CharacteristicId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

internal interface KablePeripheralFactory {
    fun create(advertisement: Advertisement): KablePeripheralAdapter

    fun create(identifier: String): KablePeripheralAdapter
}

internal interface KablePeripheralAdapter {
    val isConnected: Boolean

    suspend fun connect()

    suspend fun disconnect()

    fun close()

    fun discoveredServiceCount(): Int

    /** Returns the list of discovered GATT service UUIDs as lowercase strings, or null if not yet discovered. */
    fun discoveredServiceUuids(): List<String>?

    suspend fun maximumWriteValueLengthWithResponse(): Int

    suspend fun read(characteristicId: CharacteristicId): ByteArray

    suspend fun writeWithResponse(
        characteristicId: CharacteristicId,
        payload: ByteArray,
    )

    fun observe(characteristicId: CharacteristicId): Flow<ByteArray>
}

@Singleton
internal class DefaultKablePeripheralFactory
    @Inject
    constructor() : KablePeripheralFactory {
        override fun create(advertisement: Advertisement): KablePeripheralAdapter =
            RealKablePeripheralAdapter(
                Peripheral(advertisement, ::configurePeripheral),
            )

        override fun create(identifier: String): KablePeripheralAdapter =
            RealKablePeripheralAdapter(
                Peripheral(identifier, ::configurePeripheral),
            )

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

        private companion object {
            const val KABLE_LOG_IDENTIFIER = "Frameport BLE"
        }
    }

private class RealKablePeripheralAdapter(
    private val peripheral: Peripheral,
) : KablePeripheralAdapter {
    private val state: StateFlow<State> = peripheral.state
    private val services: StateFlow<List<DiscoveredService>?> = peripheral.services
    private val characteristicLookupCache =
        KableCharacteristicLookupCache(
            services = { services.value },
        )

    override val isConnected: Boolean
        get() = state.value is State.Connected

    override suspend fun connect() {
        peripheral.connect()
    }

    override suspend fun disconnect() {
        peripheral.disconnect()
    }

    override fun close() {
        peripheral.close()
    }

    override fun discoveredServiceCount(): Int =
        requireNotNull(services.value) { "Kable connect completed without discovered services" }
            .size

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    override fun discoveredServiceUuids(): List<String>? = services.value?.map { it.serviceUuid.toString().lowercase() }

    override suspend fun maximumWriteValueLengthWithResponse(): Int =
        peripheral.maximumWriteValueLengthForType(WriteType.WithResponse)

    override suspend fun read(characteristicId: CharacteristicId): ByteArray =
        peripheral.read(characteristicFor(characteristicId))

    override suspend fun writeWithResponse(
        characteristicId: CharacteristicId,
        payload: ByteArray,
    ) {
        peripheral.write(
            characteristic = characteristicFor(characteristicId),
            data = payload,
            writeType = WriteType.WithResponse,
        )
    }

    override fun observe(characteristicId: CharacteristicId): Flow<ByteArray> =
        peripheral.observe(characteristicFor(characteristicId))

    private fun characteristicFor(characteristicId: CharacteristicId) =
        characteristicLookupCache.characteristicFor(characteristicId)
}
