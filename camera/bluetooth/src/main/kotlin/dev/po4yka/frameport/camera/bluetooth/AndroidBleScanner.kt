package dev.po4yka.frameport.camera.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.le.ScanSettings
import com.juul.kable.Filter
import com.juul.kable.ObsoleteKableApi
import com.juul.kable.Scanner
import com.juul.kable.logs.Logging
import dev.po4yka.frameport.camera.api.BleCameraAdvertisement
import dev.po4yka.frameport.camera.api.BleCameraRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kable-backed Android implementation of [BleScanner].
 *
 * Kable owns the direct [android.bluetooth.le.BluetoothLeScanner] interaction. Frameport keeps
 * only its app-facing advertisement shape and caches Kable advertisements for later connection.
 */
@Singleton
internal class AndroidBleScanner
    @Inject
    constructor(
        private val advertisementCache: KableAdvertisementCache,
    ) : BleScanner {
        @OptIn(ObsoleteKableApi::class)
        private val scanner =
            Scanner {
                filters {
                    match {
                        manufacturerData =
                            listOf(
                                Filter.ManufacturerData(
                                    id = BleConstants.MANUFACTURER_COMPANY_ID,
                                ),
                            )
                    }
                }
                scanSettings =
                    ScanSettings
                        .Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build()
                logging {
                    identifier = KABLE_LOG_IDENTIFIER
                    engine = FrameportKableLogEngine
                    level = Logging.Level.Warnings
                    format = Logging.Format.Compact
                }
            }

        /**
         * Cold flow of filtered BLE advertisements.
         *
         * Kable starts scanning when collected and stops scanning when collection is cancelled.
         * Privacy: the returned [BleCameraRef.id] may be a BLE MAC address and is never logged.
         */
        @SuppressLint("MissingPermission")
        override fun scan(): Flow<BleCameraAdvertisement> =
            scanner.advertisements
                .onStart { advertisementCache.clear() }
                .map { advertisement ->
                    val id = advertisementCache.remember(advertisement)
                    val displayName = advertisement.name ?: advertisement.peripheralName
                    Timber.d("BLE: scan result displayName=$displayName rssi=${advertisement.rssi}")
                    BleCameraAdvertisement(
                        camera =
                            BleCameraRef(
                                id = id,
                                displayName = displayName,
                            ),
                        signalStrengthDbm = advertisement.rssi,
                    )
                }

        private companion object {
            const val KABLE_LOG_IDENTIFIER = "Frameport BLE"
        }
    }
