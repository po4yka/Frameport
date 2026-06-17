package dev.po4yka.frameport.camera.bluetooth

import dev.po4yka.frameport.camera.api.BleCameraRef
import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.CharacteristicId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/**
 * Fake [GattTransport] for JVM unit tests.
 *
 * All operations are controllable via public mutable properties. No Android framework
 * types are used; tests run on the JVM without a BluetoothGatt stub.
 *
 * Default behaviour:
 * - [connect] succeeds immediately.
 * - [discoverServices] succeeds immediately.
 * - [requestMtu] returns [BleConstants.PREFERRED_MTU].
 * - [readCharacteristic] returns [defaultReadResponse].
 * - [writeCharacteristic] succeeds and records the call.
 * - [disconnect] transitions state to Disconnected.
 */
class FakeGattTransport : GattTransport {
    // -------------------------------------------------------------------------
    // Controllable state
    // -------------------------------------------------------------------------

    /** Override to make [connect] throw the given exception. */
    var connectError: Exception? = null

    /** Override to make [discoverServices] throw the given exception. */
    var discoverServicesError: Exception? = null

    /** Override to make [readCharacteristic] throw the given exception. */
    var readError: Exception? = null

    /** Override to make [writeCharacteristic] throw the given exception. */
    var writeError: Exception? = null

    var readDelayMs: Long = 0L
    var writeDelayMs: Long = 0L

    /** Default bytes returned from [readCharacteristic] (if no [readError]). */
    var defaultReadResponse: ByteArray = byteArrayOf(0x01, 0x02)

    /** Per-characteristic read responses; overrides [defaultReadResponse] when present. */
    val characteristicReadResponses: MutableMap<String, ByteArray> = mutableMapOf()

    /** Recorded write calls: list of (characteristicId.value, payload). */
    val writeCalls: MutableList<Pair<String, ByteArray>> = mutableListOf()

    /** Set this to true to make [disconnect] a no-op (stay in current state). */
    var blockDisconnect: Boolean = false

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    private val _connectionState = MutableStateFlow(BleConnectionState.Disconnected)

    /** Direct write access for tests to drive state transitions. */
    val mutableConnectionState: MutableStateFlow<BleConnectionState> = _connectionState

    // cancel-safe: StateFlow projection.
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    /** Emit notification payloads from tests via this flow. */
    val notificationSource = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)

    var connectCallCount: Int = 0
    var discoverServicesCallCount: Int = 0
    var disconnectCallCount: Int = 0

    /**
     * Concurrency tracking for [readCharacteristic]. The single-actor operation queue must
     * guarantee only ONE GATT read is ever in flight at a time; [maxConcurrentReads] records
     * the peak observed simultaneous reads. A correctly-serialized client keeps this at 1.
     */
    private var currentConcurrentReads: Int = 0
    var maxConcurrentReads: Int = 0
        private set

    // -------------------------------------------------------------------------
    // GattTransport implementation
    // -------------------------------------------------------------------------

    // cancel-safe: no shared state mutated after suspend; cancellation is safe.
    override suspend fun connect(camera: BleCameraRef) {
        connectCallCount++
        connectError?.let { throw it }
        _connectionState.value = BleConnectionState.Connecting
    }

    // cancel-safe: no shared state mutated mid-operation; cancellation is safe.
    override suspend fun discoverServices() {
        discoverServicesCallCount++
        discoverServicesError?.let { throw it }
    }

    // cancel-safe: returns a constant; no side effects.
    override suspend fun requestMtu(mtu: Int): Int = BleConstants.PREFERRED_MTU

    // cancel-safe: read is side-effect-free on fake; cancellation is safe.
    override suspend fun readCharacteristic(characteristicId: CharacteristicId): ByteArray {
        readError?.let { throw it }
        if (readDelayMs > 0L) delay(readDelayMs)
        currentConcurrentReads++
        if (currentConcurrentReads > maxConcurrentReads) maxConcurrentReads = currentConcurrentReads
        try {
            // Yield so that, if the client failed to serialize GATT ops, a second concurrent
            // read would interleave here and bump currentConcurrentReads above 1.
            yield()
            return characteristicReadResponses[characteristicId.value] ?: defaultReadResponse
        } finally {
            currentConcurrentReads--
        }
    }

    // cancel-safe: records the call and returns; no blocking.
    override suspend fun writeCharacteristic(
        characteristicId: CharacteristicId,
        payload: ByteArray,
    ) {
        writeError?.let { throw it }
        if (writeDelayMs > 0L) delay(writeDelayMs)
        writeCalls.add(characteristicId.value to payload)
    }

    // cancel-safe: idempotent; second call is a no-op.
    override suspend fun disconnect() {
        disconnectCallCount++
        if (!blockDisconnect) {
            _connectionState.value = BleConnectionState.Disconnected
        }
    }

    // cancel-safe: cold SharedFlow subscription; cancellation unsubscribes cleanly.
    override fun notificationFlow(characteristicId: CharacteristicId): Flow<ByteArray> =
        notificationSource
            .filter { (uuid, _) -> uuid == characteristicId.value }
            .map { (_, payload) -> payload }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    /** Simulate an incoming notification from the camera. */
    fun emitNotification(
        characteristicId: CharacteristicId,
        payload: ByteArray,
    ) {
        notificationSource.tryEmit(characteristicId.value to payload)
    }

    /** Reset all recorded calls and error overrides. */
    fun reset() {
        connectError = null
        discoverServicesError = null
        readError = null
        writeError = null
        writeCalls.clear()
        readDelayMs = 0L
        writeDelayMs = 0L
        connectCallCount = 0
        discoverServicesCallCount = 0
        disconnectCallCount = 0
        currentConcurrentReads = 0
        maxConcurrentReads = 0
        blockDisconnect = false
        _connectionState.value = BleConnectionState.Disconnected
    }
}
