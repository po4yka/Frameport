package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.Advertisement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AndroidBleScannerSmokeTest {
    @Test
    fun scanPermissionDeniedMapsToBluetoothScanPermissionError() =
        runTest {
            val cause = SecurityException("missing scan permission")
            val scanner =
                AndroidBleScanner(
                    advertisementCache = KableAdvertisementCache(),
                    advertisementSource = FailingKableAdvertisementSource(cause),
                )

            val result = runCatching { scanner.scan().first() }
            val failure = result.exceptionOrNull() as BleTransportException.PermissionDenied

            assertEquals("android.permission.BLUETOOTH_SCAN", failure.permission)
            assertSame(cause, failure.cause)
        }

    @Test
    fun scanBluetoothDisabledMapsToBluetoothDisabledError() =
        runTest {
            val cause = IllegalStateException("Bluetooth adapter is disabled")
            val scanner =
                AndroidBleScanner(
                    advertisementCache = KableAdvertisementCache(),
                    advertisementSource = FailingKableAdvertisementSource(cause),
                )

            val result = runCatching { scanner.scan().first() }
            val failure = result.exceptionOrNull()

            assertEquals(BleTransportException.BluetoothDisabled::class, failure!!::class)
            assertSame(cause, failure.cause)
        }

    private class FailingKableAdvertisementSource(
        private val failure: Throwable,
    ) : KableAdvertisementSource {
        override val advertisements: Flow<Advertisement> =
            flow {
                throw failure
            }
    }
}
