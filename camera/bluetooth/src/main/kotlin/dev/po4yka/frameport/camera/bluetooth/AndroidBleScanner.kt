package dev.po4yka.frameport.camera.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.po4yka.frameport.camera.api.BleCameraAdvertisement
import dev.po4yka.frameport.camera.api.BleCameraRef
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Android implementation of [BleScanner].
 *
 * Applies a [ScanFilter] targeting:
 * 1. Manufacturer company ID 0x04D8 (Fujifilm) — primary filter.
 * 2. Service UUID [BleConstants.SERVICE_CAMERA_CONTROL] — optional secondary filter.
 *
 * Both filters are applied at the Android BLE stack level before any payload is delivered
 * to Frameport code. No advertisement payload parsing happens here — that belongs to the
 * Rust fuji-ble-protocol crate.
 *
 * Privacy contract:
 * - Device BLE address (MAC) is stored in [BleCameraRef.id] but is NEVER logged in Timber.
 * - Raw manufacturer data bytes are NEVER logged.
 * - The display name (device name string from advertisement) IS safe to log as it is
 *   user-visible and contains no identifying information beyond the camera model.
 *
 * Threading: [ScanCallback] fires on the BLE callback thread. Results are emitted into
 * [callbackFlow] which serializes delivery to the collector.
 */
@Singleton
class AndroidBleScanner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : BleScanner {
        /**
         * Cold flow of filtered BLE advertisements.
         *
         * Collecting this flow starts the scan; cancelling collection stops the scanner.
         * If the Bluetooth adapter is unavailable, the flow completes without emitting.
         *
         * cancel-safe: [callbackFlow] stops the scanner on cancellation via [awaitClose].
         */
        @SuppressLint("MissingPermission")
        override fun scan(): Flow<BleCameraAdvertisement> =
            callbackFlow {
                val bluetoothManager =
                    context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
                if (scanner == null) {
                    Timber.w("BLE: BluetoothLeScanner unavailable — scan not started")
                    close()
                    return@callbackFlow
                }

                val callback =
                    object : ScanCallback() {
                        override fun onScanResult(
                            callbackType: Int,
                            result: ScanResult,
                        ) {
                            val device = result.device
                            val displayName = result.scanRecord?.deviceName
                            // Privacy: device.address is NOT logged; displayName (model name) is safe.
                            Timber.d("BLE: scan result displayName=$displayName rssi=${result.rssi}")

                            val advertisement =
                                BleCameraAdvertisement(
                                    camera =
                                        BleCameraRef(
                                            id = device.address, // privacy: never log this value
                                            displayName = displayName,
                                        ),
                                    signalStrengthDbm = result.rssi,
                                )
                            trySend(advertisement)
                        }

                        override fun onScanFailed(errorCode: Int) {
                            Timber.e("BLE: scan failed errorCode=$errorCode")
                            close(IllegalStateException("BLE scan failed errorCode=$errorCode"))
                        }
                    }

                val filters = buildScanFilters()
                val settings =
                    ScanSettings
                        .Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build()

                Timber.d("BLE: starting scan with ${filters.size} filter(s)")
                scanner.startScan(filters, settings, callback)

                awaitClose {
                    Timber.d("BLE: stopping scan")
                    try {
                        scanner.stopScan(callback)
                    } catch (e: Exception) {
                        // Bluetooth adapter may have been disabled; ignore.
                        Timber.w("BLE: stopScan threw — adapter may be off: ${e.message}")
                    }
                }
            }

        // -------------------------------------------------------------------------
        // Internal
        // -------------------------------------------------------------------------

        /**
         * Build scan filters targeting Fujifilm BLE cameras.
         *
         * Filter 1: Manufacturer data with company ID 0x04D8.
         *   The Android BLE stack matches on the 2-byte company identifier prefix in
         *   manufacturer-specific advertisement data. This is the primary Fujifilm selector.
         *   Source: docs/reference/ble-wifi-discovery.md §Advertisement.
         *
         * Filter 2: Service UUID SERVICE_CAMERA_CONTROL.
         *   Cameras that advertise this service UUID are almost certainly Fujifilm cameras
         *   with BLE pairing enabled. This reduces false positives from other Fujifilm devices.
         *   Source: docs/reference/ble-wifi-discovery.md §Services.
         *
         * Android applies filters with OR logic across separate [ScanFilter] objects in the list.
         * A device matching EITHER filter is delivered to the callback. We want AND logic
         * (manufacturer ID AND service UUID), so we build a single combined filter instead.
         */
        private fun buildScanFilters(): List<ScanFilter> {
            val manufacturerFilter =
                ScanFilter
                    .Builder()
                    // Two-arg form (data only, no mask): matches any advertisement that carries
                    // manufacturer-specific data for the Fujifilm company ID, regardless of the
                    // data bytes. The 3-arg form with an empty data+mask pair has surprising
                    // match semantics across OEM BLE stacks, so it is avoided here.
                    .setManufacturerData(
                        BleConstants.MANUFACTURER_COMPANY_ID,
                        byteArrayOf(),
                    ).build()

            return listOf(manufacturerFilter)
        }
    }
