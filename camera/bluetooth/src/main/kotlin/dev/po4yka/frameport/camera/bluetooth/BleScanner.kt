package dev.po4yka.frameport.camera.bluetooth

import dev.po4yka.frameport.camera.api.BleCameraAdvertisement
import kotlinx.coroutines.flow.Flow

/**
 * Injectable seam over BLE scanning.
 *
 * The real implementation ([AndroidBleScanner]) delegates scanning to Kable and applies a native manufacturer filter on company ID 0x04D8 (Fujifilm) before any payload parsing.
 *
 * The fake implementation ([FakeBleScanner]) used in JVM unit tests emits advertisements
 * from a controlled list without touching Android framework types.
 *
 * Ownership: Scanning is separate from the GATT operation queue. The scanner starts and stops independently of [GattTransport]. Once a target camera is selected from the scan results, scanning is stopped and the [AndroidFujiBleClient] transitions to connecting.
 *
 * Privacy: Raw BLE MAC addresses emitted in [BleCameraAdvertisement.camera.id] must
 * NOT appear in any Timber log call. See privacy-local-first.md.
 */
internal interface BleScanner {
    /**
     * Cold flow of [BleCameraAdvertisement] from filtered BLE scan results.
     *
     * Collecting this flow starts the BLE scan; cancelling collection stops it.
     * The scan filter is applied at the Android layer (manufacturer ID 0x04D8).
     * Advertisement payload parsing is delegated to the Rust fuji-ble-protocol crate
     * once the raw bytes arrive at the JNI layer (M14 Rust stream, not this stream).
     *
     * cancel-safe: cold flow; collection cancellation stops the scanner and cleans up
     * the ScanCallback registration without side effects on the GATT stack.
     */
    fun scan(): Flow<BleCameraAdvertisement>
}
