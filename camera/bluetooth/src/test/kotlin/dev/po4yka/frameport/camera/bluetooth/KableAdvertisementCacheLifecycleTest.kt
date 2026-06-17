package dev.po4yka.frameport.camera.bluetooth

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class KableAdvertisementCacheLifecycleTest {
    @Test
    fun scannerClearsCachedAdvertisementsWhenScanStarts() {
        val source = source("AndroidBleScanner.kt")

        assertTrue(source.contains(".onStart { advertisementCache.clear() }"))
    }

    @Test
    fun gattTransportRetainsCachedAdvertisementsAcrossConnectAndDisconnect() {
        val source = source("AndroidGattTransport.kt")

        assertTrue(source.contains("override suspend fun disconnect()"))
        assertFalse(source.contains("advertisementCache.clear()"))
    }

    private fun source(fileName: String): String =
        listOf(
            File("src/main/kotlin/dev/po4yka/frameport/camera/bluetooth/$fileName"),
            File("camera/bluetooth/src/main/kotlin/dev/po4yka/frameport/camera/bluetooth/$fileName"),
        ).first { it.exists() }
            .readText()
}
