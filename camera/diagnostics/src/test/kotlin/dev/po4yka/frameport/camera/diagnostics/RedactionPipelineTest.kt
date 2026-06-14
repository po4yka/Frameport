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
}
