package dev.po4yka.frameport.camera.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KableLoggingPrivacyTest {
    @Test
    fun scannerUsesNonIdentifyingWarningsOnlyKableLogging() {
        val source = source("KableAdvertisementSource.kt")

        assertPrivacyLoggingConfiguration(source)
    }

    @Test
    fun peripheralUsesNonIdentifyingWarningsOnlyKableLogging() {
        val source = source("KablePeripheralFactory.kt")

        assertPrivacyLoggingConfiguration(source)
    }

    @Test
    fun kableLoggingNeverEnablesPayloadDataLogs() {
        val scannerSource = source("KableAdvertisementSource.kt")
        val peripheralSource = source("KablePeripheralFactory.kt")

        assertFalse(scannerSource.contains("Logging.Level.Data"))
        assertFalse(peripheralSource.contains("Logging.Level.Data"))
    }

    private fun assertPrivacyLoggingConfiguration(source: String) {
        assertTrue(source.contains("identifier = KABLE_LOG_IDENTIFIER"))
        assertTrue(source.contains("const val KABLE_LOG_IDENTIFIER = \"Frameport BLE\""))
        assertTrue(source.contains("engine = FrameportKableLogEngine"))
        assertTrue(source.contains("level = Logging.Level.Warnings"))
        assertTrue(source.contains("format = Logging.Format.Compact"))
    }

    private fun source(fileName: String): String =
        listOf(
            File("src/main/kotlin/dev/po4yka/frameport/camera/bluetooth/$fileName"),
            File("camera/bluetooth/src/main/kotlin/dev/po4yka/frameport/camera/bluetooth/$fileName"),
        ).first { it.exists() }
            .readText()
}
