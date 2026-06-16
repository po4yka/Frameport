package dev.po4yka.frameport.camera.bluetooth

import dev.po4yka.frameport.camera.api.BleCameraRef
import dev.po4yka.frameport.camera.api.CharacteristicId
import kotlinx.coroutines.CompletableDeferred

/**
 * Sealed hierarchy of operations enqueued into the single-actor GATT operation queue.
 *
 * Each operation carries a [CompletableDeferred] that resolves once the operation completes
 * (success or failure). The actor processes exactly ONE operation at a time.
 *
 * Threading: All operations are dispatched from the actor coroutine — never called
 * concurrently on the underlying GATT implementation.
 */
internal sealed class GattOperation {
    /**
     * Connect to a BLE device and discover services, then negotiate MTU.
     * Completes with Unit on success, or an exception on failure.
     */
    data class Connect(
        val camera: BleCameraRef,
        val deferred: CompletableDeferred<Unit>,
    ) : GattOperation()

    /**
     * Read the value of a characteristic.
     * Completes with the raw byte array from the characteristic on success.
     */
    data class Read(
        val characteristicId: CharacteristicId,
        val deferred: CompletableDeferred<ByteArray>,
    ) : GattOperation()

    /**
     * Write a value to a characteristic (WRITE_TYPE_DEFAULT — expects response).
     * Completes with Unit on success.
     */
    data class Write(
        val characteristicId: CharacteristicId,
        val payload: ByteArray,
        val deferred: CompletableDeferred<Unit>,
    ) : GattOperation() {
        // Generated equals/hashCode intentionally omitted: ByteArray identity equality is fine
        // for the operation queue where each Write is a distinct dispatch.
    }

    /**
     * Enable (or disable) CCCD notifications for a characteristic.
     * Requests notification setup when the transport supports an explicit subscription step.
     * Completes with Unit on success.
     */
    data class SetNotify(
        val characteristicId: CharacteristicId,
        val enable: Boolean,
        val deferred: CompletableDeferred<Unit>,
    ) : GattOperation()

    /**
     * Disconnect the GATT connection and cancel all pending operations.
     * Completes immediately (idempotent).
     */
    data object Disconnect : GattOperation()
}
