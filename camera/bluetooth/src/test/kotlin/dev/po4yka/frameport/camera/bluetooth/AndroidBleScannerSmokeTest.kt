package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.Advertisement
import com.juul.kable.ManufacturerData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AndroidBleScannerSmokeTest {
    private val logRecorder = ScanLogRecordingTree()

    @Before
    fun setUp() {
        Timber.plant(logRecorder)
    }

    @After
    fun tearDown() {
        Timber.uproot(logRecorder)
        logRecorder.clear()
    }

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

    /**
     * Privacy: scan result log must NOT contain the camera display name.
     * The display name may identify the user's camera (e.g. "FUJIFILM-X-T5-A1B2C3") and
     * must be omitted from all log output per privacy-local-first.md.
     */
    @Test
    fun scanLogDoesNotContainDisplayName() =
        runTest {
            val sensitiveDisplayName = "FUJIFILM-X-T5-ABCDEF"
            val scanner =
                AndroidBleScanner(
                    advertisementCache = KableAdvertisementCache(),
                    advertisementSource =
                        SingleAdvertisementSource(
                            FakeAdvertisement(name = sensitiveDisplayName, rssi = -70),
                        ),
                )

            runCatching { scanner.scan().first() }

            assertFalse(
                "Camera display name must NEVER appear in any Timber log from scan()",
                logRecorder.messages().any { it.contains(sensitiveDisplayName) },
            )
        }

    private class FailingKableAdvertisementSource(
        private val failure: Throwable,
    ) : KableAdvertisementSource {
        override val advertisements: Flow<Advertisement> =
            flow {
                throw failure
            }
    }

    private class SingleAdvertisementSource(
        private val advertisement: Advertisement,
    ) : KableAdvertisementSource {
        override val advertisements: Flow<Advertisement> =
            flow {
                emit(advertisement)
            }
    }

    /**
     * Minimal [Advertisement] stub sufficient for [AndroidBleScanner] to process one scan result.
     * Uses only the [name] and [rssi] fields accessed by the scanner; all other fields return
     * safe defaults. The UUID API is experimental in Kotlin stdlib and guarded with @OptIn.
     */
    @OptIn(ExperimentalUuidApi::class)
    private class FakeAdvertisement(
        override val name: String?,
        override val rssi: Int,
    ) : Advertisement {
        override val peripheralName: String? get() = name
        override val identifier: String get() = "AA:BB:CC:DD:EE:FF"
        override val isConnectable: Boolean? get() = null
        override val txPower: Int? get() = null
        override val uuids: List<Uuid> get() = emptyList()
        override val manufacturerData: ManufacturerData? get() = null

        override fun manufacturerData(companyIdentifierCode: Int): ByteArray? = null

        override fun serviceData(uuid: Uuid): ByteArray? = null
    }

    private class ScanLogRecordingTree : Timber.Tree() {
        private val recorded = mutableListOf<String>()

        override fun log(
            priority: Int,
            tag: String?,
            message: String,
            t: Throwable?,
        ) {
            recorded.add(message)
            t?.message?.let { recorded.add(it) }
        }

        fun messages(): List<String> = recorded.toList()

        fun clear() = recorded.clear()
    }
}
