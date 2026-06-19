package dev.po4yka.frameport.camera.bluetooth

import android.annotation.SuppressLint
import dev.po4yka.frameport.camera.api.BleCameraAdvertisement
import dev.po4yka.frameport.camera.api.BleCameraRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
        private val advertisementSource: KableAdvertisementSource,
    ) : BleScanner {
        /**
         * Cold flow of filtered BLE advertisements.
         *
         * Kable starts scanning when collected and stops scanning when collection is cancelled.
         * Privacy: the returned [BleCameraRef.id] may be a BLE MAC address and is never logged.
         */
        @SuppressLint("MissingPermission")
        override fun scan(): Flow<BleCameraAdvertisement> =
            advertisementSource.advertisements
                .onStart { advertisementCache.clear() }
                .map { advertisement ->
                    val id = advertisementCache.remember(advertisement)
                    val displayName = advertisement.name ?: advertisement.peripheralName
                    // Privacy: displayName may contain a device name that identifies the user's
                    // camera — never log it. Log only the signal strength as a diagnostic proxy.
                    Timber.d("BLE: scan result rssi=${advertisement.rssi}")
                    BleCameraAdvertisement(
                        camera =
                            BleCameraRef(
                                id = id,
                                displayName = displayName,
                            ),
                        signalStrengthDbm = advertisement.rssi,
                    )
                }.catch { e -> throw e.asBleScanFailure() }
    }
