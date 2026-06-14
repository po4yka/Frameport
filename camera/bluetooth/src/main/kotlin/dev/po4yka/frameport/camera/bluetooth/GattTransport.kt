package dev.po4yka.frameport.camera.bluetooth

import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.CharacteristicId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Injectable seam over the Android BluetoothGatt API.
 *
 * This interface decouples the GATT operation queue and reconnect logic in
 * [AndroidFujiBleClient] from real Android framework types, allowing full
 * JVM-unit-test coverage with [FakeGattTransport] without a real BLE stack.
 *
 * All methods on this interface are called exclusively from the single-actor
 * operation queue coroutine — they must NEVER be called concurrently. The
 * interface itself carries no threading guarantees; the caller (the actor)
 * is responsible for serialization.
 *
 * Ownership: [AndroidGattTransport] holds the real [android.bluetooth.BluetoothGatt]
 * reference. This interface exposes only the subset of GATT operations required
 * by [AndroidFujiBleClient]. Scanning is owned separately by [BleScanner].
 */
internal interface GattTransport {
    /**
     * Reactive connection state view. The implementation keeps this updated
     * as the underlying GATT connection transitions states.
     * Read from any coroutine context; updates are delivered on the emitter's context.
     */
    val connectionState: StateFlow<BleConnectionState>

    /**
     * Cold flow of incoming characteristic notification payloads for the given
     * [characteristicId]. Emissions happen on the GATT callback thread; collectors
     * should use [kotlinx.coroutines.flow.flowOn] to switch to a safe context.
     *
     * cancel-safe: cold SharedFlow subscription; collection cancellation unsubscribes cleanly.
     */
    fun notificationFlow(characteristicId: CharacteristicId): Flow<ByteArray>

    /**
     * Initiate a GATT connection to the device identified during scan.
     * Suspends until [android.bluetooth.BluetoothGattCallback.onConnectionStateChange]
     * fires with CONNECTED, or throws on failure/timeout.
     *
     * Must be called from the actor coroutine only.
     * cancel-safe: if cancelled before CONNECTED, the underlying connect attempt is
     * aborted by the caller via [disconnect].
     */
    suspend fun connect()

    /**
     * Discover GATT services after a successful [connect].
     * Suspends until [android.bluetooth.BluetoothGattCallback.onServicesDiscovered]
     * fires, or throws on failure/timeout.
     *
     * Must be called from the actor coroutine only.
     * cancel-safe: no mutable shared state mutated mid-discovery; cancellation is safe.
     */
    suspend fun discoverServices()

    /**
     * Request an MTU of [mtu] bytes after service discovery.
     * Suspends until [android.bluetooth.BluetoothGattCallback.onMtuChanged]
     * fires, or returns the current MTU on failure (MTU negotiation is best-effort).
     *
     * Must be called from the actor coroutine only.
     * cancel-safe: MTU negotiation is advisory; cancellation leaves the connection intact.
     */
    suspend fun requestMtu(mtu: Int): Int

    /**
     * Read the value of [characteristicId].
     * Suspends until [android.bluetooth.BluetoothGattCallback.onCharacteristicRead]
     * fires, or throws on failure/timeout.
     *
     * Must be called from the actor coroutine only.
     * cancel-safe: read op is atomic at the GATT level; cancellation after dispatch
     * means the result is discarded but no state is corrupted.
     */
    suspend fun readCharacteristic(characteristicId: CharacteristicId): ByteArray

    /**
     * Write [payload] to [characteristicId] (WRITE_TYPE_DEFAULT — awaits response).
     * Suspends until [android.bluetooth.BluetoothGattCallback.onCharacteristicWrite]
     * fires, or throws on failure/timeout.
     *
     * Must be called from the actor coroutine only.
     * cancel-safe: write is dispatched as an atomic unit; the camera may or may not
     * apply the write if the coroutine is cancelled mid-flight, but local state is clean.
     */
    suspend fun writeCharacteristic(
        characteristicId: CharacteristicId,
        payload: ByteArray,
    )

    /**
     * Enable (or disable) CCCD notifications for [characteristicId].
     * Calls [android.bluetooth.BluetoothGatt.setCharacteristicNotification] then
     * writes the CCCD descriptor (0x0001 to enable, 0x0000 to disable).
     * Suspends until the descriptor write callback fires.
     *
     * Must be called from the actor coroutine only.
     * cancel-safe: descriptor write is atomic; cancellation mid-flight is safe.
     */
    suspend fun setNotification(
        characteristicId: CharacteristicId,
        enable: Boolean,
    )

    /**
     * Disconnect and close the GATT connection. Idempotent.
     * May be called from any coroutine context.
     *
     * cancel-safe: idempotent; a second call is a no-op.
     */
    suspend fun disconnect()
}
