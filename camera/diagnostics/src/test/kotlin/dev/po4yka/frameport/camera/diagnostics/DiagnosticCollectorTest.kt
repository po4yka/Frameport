package dev.po4yka.frameport.camera.diagnostics

import dev.po4yka.frameport.camera.api.DiagnosticCategory
import dev.po4yka.frameport.camera.api.ErrorLayer
import dev.po4yka.frameport.camera.api.diagnosticEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression coverage for diagnostics ingestion redaction.
 *
 * All values are synthetic. These tests prove redaction is enforced by the
 * production repository/collector path, not by individual call sites.
 */
class DiagnosticCollectorTest {
    private lateinit var timeline: DiagnosticTimeline
    private lateinit var repository: DiagnosticsRepositoryImpl

    @Before
    fun setUp() {
        timeline = DiagnosticTimeline()
        val collector = DiagnosticCollector(timeline, RedactionPipeline())
        repository = DiagnosticsRepositoryImpl(timeline, collector)
    }

    @Test
    fun recordEvent_redactsMessageMetadataAndSessionIdBeforeTimelineStorage() =
        runTest {
            repository.recordEvent(
                diagnosticEvent(
                    layer = ErrorLayer.Wifi,
                    category = DiagnosticCategory.WifiEvent,
                    message =
                        "connect failed ip=192.168.0.1 mac=AA:BB:CC:DD:EE:FF " +
                            "serial=3AB12345 passphrase=hunter2 file=DSCF1234.RAF socketFd=42",
                    metadata =
                        mapOf(
                            "ssid" to "ssid=Home Camera WiFi",
                            "path" to "/sdcard/DCIM/DSCF1234.RAF",
                            "location" to "41.7151, 44.8271",
                        ),
                    sessionId = "AA:BB:CC:DD:EE:FF",
                ),
            )

            val event = timeline.timeline.value.single()
            val combined = event.message + " " + event.metadata.values.joinToString(" ") + " " + event.sessionId

            assertFalse(combined.contains("192.168.0.1"))
            assertFalse(combined.contains("AA:BB:CC:DD:EE:FF"))
            assertFalse(combined.contains("3AB12345"))
            assertFalse(combined.contains("hunter2"))
            assertFalse(combined.contains("Home Camera WiFi"))
            assertFalse(combined.contains("DSCF1234.RAF"))
            assertFalse(combined.contains("socketFd=42"))
            assertFalse(combined.contains("41.7151"))
            assertFalse(combined.contains("44.8271"))
            assertTrue(combined.contains("<redacted-ip>"))
            assertTrue(combined.contains("<redacted-mac>"))
            assertTrue(combined.contains("<redacted-serial>"))
            assertTrue(combined.contains("<redacted-secret>"))
            assertTrue(combined.contains("<redacted-ssid>"))
            assertTrue(combined.contains("<redacted-filename>"))
            assertTrue(combined.contains("<redacted-gps>"))
            assertTrue(combined.contains("<redacted-fd>"))
        }

    @Test
    fun recordEvent_preservesSafeDiagnosticShape() =
        runTest {
            repository.recordEvent(
                diagnosticEvent(
                    layer = ErrorLayer.Protocol,
                    category = DiagnosticCategory.ProtocolEvent,
                    message = "PTP response rejected",
                    metadata = mapOf("responseCode" to "0x2001"),
                    sessionId = "session-42",
                ),
            )

            val event = timeline.timeline.value.single()
            assertEquals("PTP response rejected", event.message)
            assertEquals("0x2001", event.metadata["responseCode"])
            assertEquals("session-42", event.sessionId)
        }

    // H-5: metadata keys are redacted, not only values
    @Test
    fun recordEvent_redactsMetadataKeysContainingMacAddress() =
        runTest {
            // A raw MAC used as a metadata key must not appear in the stored event.
            repository.recordEvent(
                diagnosticEvent(
                    layer = ErrorLayer.Bluetooth,
                    category = DiagnosticCategory.BluetoothEvent,
                    message = "device connected",
                    metadata = mapOf("AA:BB:CC:DD:EE:FF" to "connected"),
                    sessionId = "session-ble-01",
                ),
            )

            val event = timeline.timeline.value.single()
            val allKeys = event.metadata.keys.joinToString(" ")
            assertFalse(
                "Raw MAC must not appear as a metadata key after redaction",
                allKeys.contains("AA:BB:CC:DD:EE:FF"),
            )
            assertTrue(
                "Redacted MAC sentinel must appear in the key",
                allKeys.contains("<redacted-mac>"),
            )
        }
}
