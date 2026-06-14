package dev.po4yka.frameport.camera.bluetooth

import dev.po4yka.frameport.camera.api.BleCameraAdvertisement
import kotlinx.coroutines.flow.Flow

/**
 * Injectable seam over [android.bluetooth.le.BluetoothLeScanner].
 *
 * The real implementation ([AndroidBleScanner]) applies a [android.bluetooth.le.ScanFilter]
 * on manufacturer company ID 0x04D8 (Fujifilm) and optionally on the SERVICE_CAMERA_CONTROL
 * UUID, delegating device selection to the Android BLE stack before any payload parsing.
 *
 * The fake implementation ([FakeBleScanner]) used in JVM unit tests emits advertisements
 * from a controlled list without touching Android framework types.
 *
 * Ownership: Scanning is separate from the GATT operation queue. The scanner starts and
 * stops independently of [GattTransport]. Once a target camera is selected from the scan
 * results, scanning is stopped and the [AndroidFujiBleClient] transitions to connecting.
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
