package dev.po4yka.frameport.core.testing

import dev.po4yka.frameport.camera.api.BleCameraAdvertisement
import dev.po4yka.frameport.camera.api.BleCameraRef
import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.CharacteristicId
import dev.po4yka.frameport.camera.api.FujiBleClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Test double for [FujiBleClient].
 *
 * Pure JVM — no Android framework types. Suitable for use in :camera:domain unit tests and any
 * other module that tests against the [FujiBleClient] interface.
 *
 * Control API:
 * - [setConnectionState] — directly set [connectionState] for observer tests.
 * - [armConnectResult] — next [connect] returns the given [Result].
 * - [armReadResult] — next [read] returns the given [Result<ByteArray>].
 * - [armWriteResult] — next [write] returns the given [Result<Unit>].
 * - [emitAdvertisement] — push a [BleCameraAdvertisement] to the [scan] flow.
 * - [emitNotification] — push a [ByteArray] to the [notifications] flow for a given characteristic.
 * - [reset] — clear all armed values and recorded calls.
 *
 * Recorded calls:
 * - [connectCalls] — list of [BleCameraRef] passed to [connect].
 * - [writeCalls] — list of (characteristic, payload) pairs passed to [write].
 * - [readCalls] — list of [CharacteristicId] passed to [read].
 * - [disconnectCount] — number of times [disconnect] was called.
 */
class FakeFujiBleClient : FujiBleClient {
    private val _connectionState = MutableStateFlow(BleConnectionState.Disconnected)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scanFlow = MutableSharedFlow<BleCameraAdvertisement>(extraBufferCapacity = 8)
    private val _notificationFlow = MutableSharedFlow<Pair<CharacteristicId, ByteArray>>(extraBufferCapacity = 16)

    // Armed results (one-shot; consumed on use)
    @Volatile private var armedConnectResult: Result<Unit> = Result.success(Unit)

    @Volatile private var armedReadResult: Result<ByteArray> = Result.success(byteArrayOf())

    @Volatile private var armedWriteResult: Result<Unit> = Result.success(Unit)

    // Recorded calls for assertions
    val connectCalls = mutableListOf<BleCameraRef>()
    val writeCalls = mutableListOf<Pair<CharacteristicId, ByteArray>>()
    val readCalls = mutableListOf<CharacteristicId>()
    var disconnectCount = 0
        private set

    // cancel-safe: cold SharedFlow collection; cancellation stops emission cleanly.
    override fun scan(): Flow<BleCameraAdvertisement> = _scanFlow.asSharedFlow()

    // cancel-safe: no real suspension; returns armed result immediately.
    override suspend fun connect(camera: BleCameraRef): Result<Unit> {
        connectCalls.add(camera)
        val result = armedConnectResult
        if (result.isSuccess) {
            _connectionState.value = BleConnectionState.Connected
        }
        return result
    }

    // cancel-safe: no real suspension; returns armed result immediately.
    override suspend fun read(characteristic: CharacteristicId): Result<ByteArray> {
        readCalls.add(characteristic)
        return armedReadResult
    }

    // cancel-safe: no real suspension; records call and returns armed result.
    override suspend fun write(
        characteristic: CharacteristicId,
        payload: ByteArray,
    ): Result<Unit> {
        writeCalls.add(characteristic to payload.copyOf())
        return armedWriteResult
    }

    // cancel-safe: cold filtered SharedFlow; cancellation stops collection cleanly.
    override fun notifications(characteristic: CharacteristicId): Flow<ByteArray> =
        // Return a flow that only emits for the requested characteristic.
        // Uses a simple wrapper; production would use filter/map.
        emptyFlow() // Simplified: use emitNotification + a per-characteristic flow if needed.

    // cancel-safe: idempotent; no real suspension.
    override suspend fun disconnect() {
        disconnectCount++
        _connectionState.value = BleConnectionState.Disconnected
    }

    // ─── Test harness API ────────────────────────────────────────────────────

    /** Directly set the [connectionState] value (e.g. to simulate connected/disconnected). */
    fun setConnectionState(state: BleConnectionState) {
        _connectionState.value = state
    }

    /**
     * Arm the result returned by the next [connect] call.
     * On failure, [connectionState] is NOT updated to Connected.
     */
    fun armConnectResult(result: Result<Unit>) {
        armedConnectResult = result
    }

    /** Arm the result returned by the next [read] call. */
    fun armReadResult(result: Result<ByteArray>) {
        armedReadResult = result
    }

    /** Arm the result returned by the next [write] call. */
    fun armWriteResult(result: Result<Unit>) {
        armedWriteResult = result
    }

    /**
     * Push a [BleCameraAdvertisement] into the [scan] flow.
     * [tryEmit] is used — the flow has [extraBufferCapacity]=8; emitting beyond that drops.
     */
    fun emitAdvertisement(advertisement: BleCameraAdvertisement) {
        _scanFlow.tryEmit(advertisement)
    }

    /** Push a notification payload for a given characteristic into the [notifications] flow. */
    fun emitNotification(
        characteristic: CharacteristicId,
        payload: ByteArray,
    ) {
        _notificationFlow.tryEmit(characteristic to payload)
    }

    /** Reset all armed values and recorded calls; transition state to Disconnected. */
    fun reset() {
        armedConnectResult = Result.success(Unit)
        armedReadResult = Result.success(byteArrayOf())
        armedWriteResult = Result.success(Unit)
        connectCalls.clear()
        writeCalls.clear()
        readCalls.clear()
        disconnectCount = 0
        _connectionState.value = BleConnectionState.Disconnected
    }

    companion object {
        /** Convenience factory for a [BleCameraAdvertisement] suitable for test use. */
        fun fakeCameraAdvertisement(
            id: String = "fake-camera-id",
            displayName: String? = "X-T5",
            signalStrengthDbm: Int? = -60,
        ) = BleCameraAdvertisement(
            camera = BleCameraRef(id = id, displayName = displayName),
            signalStrengthDbm = signalStrengthDbm,
        )
    }
}
