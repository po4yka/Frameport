package dev.po4yka.frameport.camera.bluetooth

import android.bluetooth.le.ScanSettings
import com.juul.kable.Advertisement
import com.juul.kable.Filter
import com.juul.kable.ObsoleteKableApi
import com.juul.kable.Scanner
import com.juul.kable.logs.Logging
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

internal interface KableAdvertisementSource {
    val advertisements: Flow<Advertisement>
}

@Singleton
internal class DefaultKableAdvertisementSource
    @Inject
    constructor() : KableAdvertisementSource {
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

        override val advertisements: Flow<Advertisement> = scanner.advertisements

        private companion object {
            const val KABLE_LOG_IDENTIFIER = "Frameport BLE"
        }
    }
