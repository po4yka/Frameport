package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.Advertisement
import com.juul.kable.Characteristic
import com.juul.kable.DiscoveredService
import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import com.juul.kable.State
import com.juul.kable.WriteType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

internal interface KablePeripheralFactory {
    fun create(
        advertisement: Advertisement,
        configure: (PeripheralBuilder) -> Unit,
    ): KablePeripheralAdapter

    fun create(
        identifier: String,
        configure: (PeripheralBuilder) -> Unit,
    ): KablePeripheralAdapter
}

internal interface KablePeripheralAdapter {
    val state: StateFlow<State>
    val services: StateFlow<List<DiscoveredService>?>

    suspend fun connect()

    suspend fun disconnect()

    fun close()

    suspend fun maximumWriteValueLengthForType(writeType: WriteType): Int

    suspend fun read(characteristic: Characteristic): ByteArray

    suspend fun write(
        characteristic: Characteristic,
        data: ByteArray,
        writeType: WriteType,
    )

    fun observe(characteristic: Characteristic): Flow<ByteArray>
}

@Singleton
internal class DefaultKablePeripheralFactory
    @Inject
    constructor() : KablePeripheralFactory {
        override fun create(
            advertisement: Advertisement,
            configure: (PeripheralBuilder) -> Unit,
        ): KablePeripheralAdapter =
            RealKablePeripheralAdapter(
                Peripheral(advertisement, configure),
            )

        override fun create(
            identifier: String,
            configure: (PeripheralBuilder) -> Unit,
        ): KablePeripheralAdapter =
            RealKablePeripheralAdapter(
                Peripheral(identifier, configure),
            )
    }

private class RealKablePeripheralAdapter(
    private val peripheral: Peripheral,
) : KablePeripheralAdapter {
    override val state: StateFlow<State> = peripheral.state
    override val services: StateFlow<List<DiscoveredService>?> = peripheral.services

    override suspend fun connect() {
        peripheral.connect()
    }

    override suspend fun disconnect() {
        peripheral.disconnect()
    }

    override fun close() {
        peripheral.close()
    }

    override suspend fun maximumWriteValueLengthForType(writeType: WriteType): Int =
        peripheral.maximumWriteValueLengthForType(writeType)

    override suspend fun read(characteristic: Characteristic): ByteArray =
        peripheral.read(characteristic)

    override suspend fun write(
        characteristic: Characteristic,
        data: ByteArray,
        writeType: WriteType,
    ) {
        peripheral.write(
            characteristic = characteristic,
            data = data,
            writeType = writeType,
        )
    }

    override fun observe(characteristic: Characteristic): Flow<ByteArray> =
        peripheral.observe(characteristic)
}
