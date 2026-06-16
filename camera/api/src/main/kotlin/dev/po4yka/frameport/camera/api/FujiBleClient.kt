package dev.po4yka.frameport.camera.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Interface for Android-owned BLE scanning, GATT connection, and characteristic I/O.
 *
 * Owns: BLE scanning, BluetoothGatt lifecycle, MTU negotiation, GATT operation queue,
 * timeout enforcement, reconnect scheduling, and permission gating.
 *
 * Does NOT own: advertisement payload parsing, characteristic payload encoding/decoding,
 * pairing logic, or Wi-Fi handoff payload interpretation. Those belong to the Rust layer
 * (fuji-ble-protocol crate).
 *
 * Threading contract: ALL BluetoothGatt method calls happen from ONE coroutine context
 * (the operation-queue actor). Only one GATT operation is in flight at a time.
 *
 * See: docs/android/bluetooth-architecture.md, docs/adr/0003-ble-client-abstraction.md
 */
interface FujiBleClient {
    val connectionState: StateFlow<BleConnectionState>

    // cancel-safe: cold Flow; cancellation stops emission cleanly.
    fun scan(): Flow<BleCameraAdvertisement>

    // cancel-safe: enqueues one connect op; cancellation removes the op from the queue before dispatch.
    suspend fun connect(camera: BleCameraRef): Result<Unit>

    // cancel-safe: enqueues one read op; cancellation removes the op from the queue before dispatch.
    suspend fun read(characteristic: CharacteristicId): Result<ByteArray>

    // cancel-safe: enqueues one write op; cancellation removes the op from the queue before dispatch.
    suspend fun write(
        characteristic: CharacteristicId,
        payload: ByteArray,
    ): Result<Unit>

    // cancel-safe: cold Flow backed by a filtered SharedFlow; cancellation stops collection cleanly.
    fun notifications(characteristic: CharacteristicId): Flow<ByteArray>

    // cancel-safe: idempotent; cancels all pending ops, which complete with BleOperationCancelled.
    suspend fun disconnect()
}

/**
 * Stable reference to a discovered BLE camera.
 *
 * PRIVACY: [id] is a hardware address or system-assigned identifier — never log in plain text.
 * See privacy-local-first.md.
 */
data class BleCameraRef(
    val id: String,
    val displayName: String?,
)

/**
 * A BLE advertisement from a discoverable camera.
 *
 * PRIVACY: [camera].id must never appear in Timber logs at any level.
 * It may appear only in a frameport-dev-logs-feature-gated tracing::trace! in Rust.
 */
data class BleCameraAdvertisement(
    val camera: BleCameraRef,
    val signalStrengthDbm: Int?,
)

/**
 * Type-safe wrapper for a BLE characteristic UUID string.
 * Values are defined in fuji-ble-protocol (Rust) constants and mirrored to Kotlin
 * as string constants in :camera:bluetooth.
 */
@JvmInline
value class CharacteristicId(
    val value: String,
)

/**
 * State machine for [FujiBleClient].
 *
 * Happy-path order: Disconnected → Scanning → Connecting → Connected
 * Terminal error state: [Failed] (entered after max reconnect attempts exhausted)
 * Tear-down path: Connected → Disconnecting → Disconnected
 */
enum class BleConnectionState {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Disconnecting,
    Failed,
}

/**
 * Parsed Wi-Fi credentials obtained from the camera over BLE.
 *
 * PRIVACY: [passphrase] must NEVER appear in any Timber.* or tracing::* call at any level.
 * See privacy-local-first.md.
 */
data class BleWifiHandoff(
    val ssid: String,
    val passphrase: String,
    val bssid: String,
)

/**
 * No-op implementation of [FujiBleClient] used before the real Android implementation
 * is wired by the :camera:bluetooth Hilt module.
 *
 * Returns failure results and empty flows; does not touch any Android BLE APIs.
 */
class NoOpFujiBleClient : FujiBleClient {
    private val _connectionState = MutableStateFlow(BleConnectionState.Disconnected)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState

    override fun scan(): Flow<BleCameraAdvertisement> = emptyFlow()

    override suspend fun connect(camera: BleCameraRef): Result<Unit> =
        Result.failure(IllegalStateException("BLE support is not yet implemented."))

    override suspend fun read(characteristic: CharacteristicId): Result<ByteArray> =
        Result.failure(IllegalStateException("BLE support is not yet implemented."))

    override suspend fun write(
        characteristic: CharacteristicId,
        payload: ByteArray,
    ): Result<Unit> = Result.failure(IllegalStateException("BLE support is not yet implemented."))

    override fun notifications(characteristic: CharacteristicId): Flow<ByteArray> = emptyFlow()

    override suspend fun disconnect() {
        _connectionState.value = BleConnectionState.Disconnected
    }
}
