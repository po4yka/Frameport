package dev.po4yka.frameport.camera.wifi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraWifiConnectorPrivacyTest {
    @Test
    fun implementationDoesNotLogSsidDerivedValuesOrRawHosts() {
        val source = connectorSource()

        assertFalse(source.contains("credentials.ssid.hashCode()"))
        assertFalse(source.contains("ssid.hash"))
        assertFalse(source.contains("-> %s:%d"))
        assertTrue(source.contains("host=[redacted]"))
    }

    @Test
    fun cachedNetworkMustMatchRequestedHandle() {
        val source = connectorSource()

        assertTrue(source.contains("cached.networkHandle.toString() == handle.value"))
    }

    private fun connectorSource(): String =
        listOf(
            File("src/main/kotlin/dev/po4yka/frameport/camera/wifi/CameraWifiConnectorImpl.kt"),
            File("camera/wifi/src/main/kotlin/dev/po4yka/frameport/camera/wifi/CameraWifiConnectorImpl.kt"),
        ).first { it.exists() }
            .readText()
}
