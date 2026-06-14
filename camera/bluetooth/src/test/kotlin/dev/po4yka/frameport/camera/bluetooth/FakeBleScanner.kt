package dev.po4yka.frameport.camera.bluetooth

import dev.po4yka.frameport.camera.api.BleCameraAdvertisement
import dev.po4yka.frameport.camera.api.BleCameraRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fake [BleScanner] for JVM unit tests.
 *
 * Tests push [BleCameraAdvertisement] values via [emit]; the [scan] flow delivers them
 * to collectors without touching any Android BLE framework type.
 */
class FakeBleScanner : BleScanner {
    private val _advertisements =
        MutableSharedFlow<BleCameraAdvertisement>(extraBufferCapacity = 64)

    /** Default camera advertisement emitted by [emitDefaultCamera]. */
    val defaultCamera =
        BleCameraAdvertisement(
            camera = BleCameraRef(id = "AA:BB:CC:DD:EE:FF", displayName = "FUJIFILM X-T5"),
            signalStrengthDbm = -65,
        )

    // cancel-safe: cold SharedFlow subscription; cancellation unsubscribes cleanly.
    override fun scan(): Flow<BleCameraAdvertisement> = _advertisements.asSharedFlow()

    /** Push an advertisement to any active collectors. */
    fun emit(advertisement: BleCameraAdvertisement) {
        _advertisements.tryEmit(advertisement)
    }

    /** Push [defaultCamera] to any active collectors. */
    fun emitDefaultCamera() = emit(defaultCamera)
}
