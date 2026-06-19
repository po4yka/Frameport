package dev.po4yka.frameport.camera.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RedactionPipeline].
 *
 * All inputs use SYNTHETIC placeholders — no real device identifiers.
 * FRAMEPORT_BLESS_GOLDENS=1 is never used here.
 */
class RedactionPipelineTest {
    private lateinit var pipeline: RedactionPipeline

    @Before
    fun setUp() {
        pipeline = RedactionPipeline()
    }

    // ── redactSerial ─────────────────────────────────────────────────────────────

    @Test
    fun redactSerial_prefixIsSerial() {
        val result = pipeline.redactSerial("SN-TEST-001")
        assertTrue("Expected 'serial:' prefix", result.startsWith("serial:"))
    }

    @Test
    fun redactSerial_doesNotEqualInput() {
        val raw = "SN-TEST-001"
        assertNotEquals(raw, pipeline.redactSerial(raw))
    }

    @Test
    fun redactSerial_deterministicSameInput() {
        val raw = "SN-TEST-001"
        assertEquals(pipeline.redactSerial(raw), pipeline.redactSerial(raw))
    }

    @Test
    fun redactSerial_distinctInputsProduceDistinctHashes() {
        val r1 = pipeline.redactSerial("SN-TEST-001")
        val r2 = pipeline.redactSerial("SN-TEST-002")
        assertNotEquals(r1, r2)
    }

    @Test
    fun redactSerial_hashIsLowercaseHex() {
        val result = pipeline.redactSerial("SN-TEST-001")
        val hash = result.removePrefix("serial:")
        assertTrue(
            "Hash should be lowercase hex, got: $hash",
            hash.matches(Regex("[0-9a-f]+")),
        )
        assertEquals("SHA-256 should be 64 hex chars", 64, hash.length)
    }

    // ── redactMac ────────────────────────────────────────────────────────────────

    @Test
    fun redactMac_prefixIsMac() {
        val result = pipeline.redactMac("AA:BB:CC:DD:EE:FF")
        assertTrue("Expected 'mac:' prefix", result.startsWith("mac:"))
    }

    @Test
    fun redactMac_doesNotEqualInput() {
        val raw = "AA:BB:CC:DD:EE:FF"
        assertNotEquals(raw, pipeline.redactMac(raw))
    }

    @Test
    fun redactMac_deterministicSameInput() {
        val raw = "AA:BB:CC:DD:EE:FF"
        assertEquals(pipeline.redactMac(raw), pipeline.redactMac(raw))
    }

    @Test
    fun redactMac_distinctInputsProduceDistinctHashes() {
        val r1 = pipeline.redactMac("AA:BB:CC:DD:EE:FF")
        val r2 = pipeline.redactMac("AA:BB:CC:DD:EE:00")
        assertNotEquals(r1, r2)
    }

    @Test
    fun redactMac_hashIsLowercaseHex() {
        val result = pipeline.redactMac("AA:BB:CC:DD:EE:FF")
        val hash = result.removePrefix("mac:")
        assertTrue(
            "Hash should be lowercase hex, got: $hash",
            hash.matches(Regex("[0-9a-f]+")),
        )
        assertEquals("SHA-256 should be 64 hex chars", 64, hash.length)
    }

    // ── redactSsid ───────────────────────────────────────────────────────────────

    @Test
    fun redactSsid_prefixIsSsid() {
        val result = pipeline.redactSsid("TestNetwork")
        assertTrue("Expected 'ssid:' prefix", result.startsWith("ssid:"))
    }

    @Test
    fun redactSsid_doesNotEqualInput() {
        val raw = "TestNetwork"
        assertNotEquals(raw, pipeline.redactSsid(raw))
    }

    @Test
    fun redactSsid_deterministicSameInput() {
        val raw = "TestNetwork"
        assertEquals(pipeline.redactSsid(raw), pipeline.redactSsid(raw))
    }

    @Test
    fun redactSsid_distinctInputsProduceDistinctHashes() {
        val r1 = pipeline.redactSsid("TestNetwork")
        val r2 = pipeline.redactSsid("OtherNetwork")
        assertNotEquals(r1, r2)
    }

    @Test
    fun redactSsid_hashIsLowercaseHex() {
        val result = pipeline.redactSsid("TestNetwork")
        val hash = result.removePrefix("ssid:")
        assertTrue(
            "Hash should be lowercase hex, got: $hash",
            hash.matches(Regex("[0-9a-f]+")),
        )
        assertEquals("SHA-256 should be 64 hex chars", 64, hash.length)
    }

    // ── redactFilename ───────────────────────────────────────────────────────────

    @Test
    fun redactFilename_alwaysReturnsSentinel() {
        assertEquals("<redacted-filename>", pipeline.redactFilename("DSCF0001.RAF"))
    }

    @Test
    fun redactFilename_differentInputsSameSentinel() {
        val a = pipeline.redactFilename("DSCF0001.RAF")
        val b = pipeline.redactFilename("DSCF9999.JPG")
        assertEquals(a, b)
        assertEquals("<redacted-filename>", a)
    }

    @Test
    fun redactFilename_doesNotLeakInput() {
        val result = pipeline.redactFilename("SN-TEST-001.RAF")
        assertFalse(result.contains("SN-TEST-001"))
    }

    // ── redactGps ────────────────────────────────────────────────────────────────

    @Test
    fun redactGps_alwaysReturnsSentinel() {
        assertEquals("<redacted-gps>", pipeline.redactGps(0.0, 0.0))
    }

    @Test
    fun redactGps_differentCoordinatesSameSentinel() {
        val a = pipeline.redactGps(37.7749, -122.4194)
        val b = pipeline.redactGps(55.7558, 37.6176)
        assertEquals(a, b)
        assertEquals("<redacted-gps>", a)
    }

    @Test
    fun redactGps_doesNotLeakCoordinates() {
        val result = pipeline.redactGps(37.7749, -122.4194)
        assertFalse(result.contains("37"))
        assertFalse(result.contains("122"))
    }

    // ── cross-method hash isolation ───────────────────────────────────────────────

    @Test
    fun sameRawInput_differentMethods_differentOutput() {
        val raw = "SN-TEST-001"
        val serial = pipeline.redactSerial(raw)
        val mac = pipeline.redactMac(raw)
        val ssid = pipeline.redactSsid(raw)
        // Prefixes differ, so outputs must differ
        assertNotEquals(serial, mac)
        assertNotEquals(serial, ssid)
        assertNotEquals(mac, ssid)
    }

    // ── redactDiagnosticText — serial (M-1) ──────────────────────────────────────

    @Test
    fun redactDiagnosticText_redactsKeywordedSerial() {
        // The keyworded rule catches "serial=<value>" structured log tokens.
        val result = pipeline.redactDiagnosticText("connect serial=3AB12345 ok")
        assertFalse("Raw serial must not appear after redaction", result.contains("3AB12345"))
        assertTrue("Redacted-serial sentinel must appear", result.contains("<redacted-serial>"))
    }

    @Test
    fun redactDiagnosticText_serialMustBePreHashedByCallSite() {
        // M-1 DOCUMENTED CONTRACT: a bare serial not preceded by a keyword is NOT
        // caught by redactDiagnosticText; call sites must use redactSerial() explicitly.
        // This test documents that expectation so it is visible in the test suite.
        // (A bare token like "3AB12345" alone in a sentence would also be redacted by the
        // serial rule because the keyword group is optional in the implementation — but
        // category words like "Transfer" or "Protocol" are intentionally not affected.)
        val safeMessage = "PTP response rejected"
        val result = pipeline.redactDiagnosticText(safeMessage)
        // Safe category-level wording must survive redaction unchanged.
        assertEquals(
            "Category-level wording must not be redacted",
            safeMessage,
            result,
        )
    }

    // ── redactDiagnosticText — IP with port (M-2) ────────────────────────────────

    @Test
    fun redactDiagnosticText_redactsIpWithPort() {
        // M-2: IP:port form must be fully redacted (not just the IP part).
        val result = pipeline.redactDiagnosticText("connect 192.168.0.1:15740 failed")
        assertFalse("IP must not appear after redaction", result.contains("192.168.0.1"))
        assertFalse(":15740 port must not appear after redaction", result.contains(":15740"))
        assertTrue("Redacted-ip sentinel must appear", result.contains("<redacted-ip>"))
    }

    @Test
    fun redactDiagnosticText_redactsBareIpWithoutPort() {
        // Existing behaviour must be preserved for bare IP addresses.
        val result = pipeline.redactDiagnosticText("camera at 192.168.0.1 ok")
        assertFalse("IP must not appear after redaction", result.contains("192.168.0.1"))
        assertTrue("Redacted-ip sentinel must appear", result.contains("<redacted-ip>"))
    }

    // ── redactDiagnosticText ─────────────────────────────────────────────────────

    @Test
    fun redactDiagnosticText_removesHighRiskDiagnosticValues() {
        val raw =
            "camera 192.168.0.1 mac AA:BB:CC:DD:EE:FF serial=3AB12345 " +
                "passphrase=hunter2 ssid=Home Camera WiFi; gps 41.7151, 44.8271 file /sdcard/DCIM/DSCF1234.RAF fd=42 socketFd=99"

        val result = pipeline.redactDiagnosticText(raw)

        assertFalse(result.contains("192.168.0.1"))
        assertFalse(result.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(result.contains("3AB12345"))
        assertFalse(result.contains("hunter2"))
        assertFalse(result.contains("Home Camera WiFi"))
        assertFalse(result.contains("41.7151"))
        assertFalse(result.contains("44.8271"))
        assertFalse(result.contains("DSCF1234.RAF"))
        assertFalse(result.contains("fd=42"))
        assertFalse(result.contains("socketFd=99"))
        assertTrue(result.contains("<redacted-ip>"))
        assertTrue(result.contains("<redacted-mac>"))
        assertTrue(result.contains("<redacted-serial>"))
        assertTrue(result.contains("<redacted-secret>"))
        assertTrue(result.contains("<redacted-ssid>"))
        assertTrue(result.contains("<redacted-gps>"))
        assertTrue(result.contains("<redacted-filename>"))
        assertTrue(result.contains("<redacted-fd>"))
    }
}
