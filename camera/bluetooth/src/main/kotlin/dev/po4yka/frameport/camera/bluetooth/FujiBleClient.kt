package dev.po4yka.frameport.camera.bluetooth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow

interface FujiBleClient {
    val connectionState: StateFlow<BleConnectionState>

    fun scan(): Flow<BleCameraAdvertisement>

    suspend fun connect(camera: BleCameraRef): Result<Unit>

    suspend fun read(characteristic: CharacteristicId): Result<ByteArray>

    suspend fun write(characteristic: CharacteristicId, payload: ByteArray): Result<Unit>

    fun notifications(characteristic: CharacteristicId): Flow<ByteArray>

    suspend fun disconnect()
}

data class BleCameraRef(
    val id: String,
    val displayName: String?,
)

data class BleCameraAdvertisement(
    val camera: BleCameraRef,
    val signalStrengthDbm: Int?,
)

@JvmInline
value class CharacteristicId(val value: String)

enum class BleConnectionState {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Disconnecting,
    Failed,
}

class NoOpFujiBleClient : FujiBleClient {
    private val state = MutableStateFlow(BleConnectionState.Disconnected)

    override val connectionState: StateFlow<BleConnectionState> = state

    override fun scan(): Flow<BleCameraAdvertisement> = emptyFlow()

    override suspend fun connect(camera: BleCameraRef): Result<Unit> =
        Result.failure(IllegalStateException("BLE support is not implemented."))

    override suspend fun read(characteristic: CharacteristicId): Result<ByteArray> =
        Result.failure(IllegalStateException("BLE support is not implemented."))

    override suspend fun write(characteristic: CharacteristicId, payload: ByteArray): Result<Unit> =
        Result.failure(IllegalStateException("BLE support is not implemented."))

    override fun notifications(characteristic: CharacteristicId): Flow<ByteArray> = emptyFlow()

    override suspend fun disconnect() {
        state.value = BleConnectionState.Disconnected
    }
}
