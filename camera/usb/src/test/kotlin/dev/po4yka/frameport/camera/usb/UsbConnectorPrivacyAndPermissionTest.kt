package dev.po4yka.frameport.camera.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UsbConnectorPrivacyAndPermissionTest {
    @Test
    fun permissionResultsAreMatchedToPendingDevice() {
        val source = connectorSource()

        assertTrue(source.contains("sameDevice(device, pending)"))
        assertTrue(source.contains("ignoring stale permission result"))
    }

    @Test
    fun implementationDoesNotLogRawDeviceKeysOrFdValues() {
        val source = connectorSource()

        assertFalse(source.contains("key=%s"))
        assertFalse(source.contains("device.deviceKey}"))
        assertFalse(source.contains("dupFd=%d"))
    }

    private fun connectorSource(): String =
        listOf(
            File("src/main/kotlin/dev/po4yka/frameport/camera/usb/AndroidCameraUsbConnector.kt"),
            File("camera/usb/src/main/kotlin/dev/po4yka/frameport/camera/usb/AndroidCameraUsbConnector.kt"),
        ).first { it.exists() }
            .readText()
}
