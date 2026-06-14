package dev.po4yka.frameport.camera.diagnostics

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stateless pipeline for redacting privacy-sensitive identifiers before they enter
 * any [dev.po4yka.frameport.camera.api.DiagnosticEvent] or log call.
 *
 * All methods are pure and deterministic: the same input always produces the same
 * output. SHA-256 is computed via [java.security.MessageDigest] — no external dep.
 *
 * PRIVACY CONTRACT:
 *  - Raw device serial numbers, MAC addresses, SSIDs, GPS coordinates, and filenames
 *    MUST NEVER appear in logs or diagnostic events.
 *  - Pass any such value through the corresponding method before use.
 *  - GPS and filenames are sentinel-redacted (fixed string); serial/MAC/SSID are hashed
 *    so downstream code can correlate events from the same device without identifying it.
 */
@Singleton
class RedactionPipeline
    @Inject
    constructor() {
        /**
         * Returns "serial:" + lowercase SHA-256 hex of [raw].
         * Deterministic: two events with the same raw serial produce matching hashes.
         */
        fun redactSerial(raw: String): String = "serial:${sha256hex(raw)}"

        /**
         * Returns "mac:" + lowercase SHA-256 hex of [raw].
         * Input should be normalised (e.g. upper-cased, colon-separated) before hashing
         * to ensure collation across call sites.
         */
        fun redactMac(raw: String): String = "mac:${sha256hex(raw)}"

        /**
         * Returns "ssid:" + lowercase SHA-256 hex of [raw].
         */
        fun redactSsid(raw: String): String = "ssid:${sha256hex(raw)}"

        /**
         * Returns the fixed sentinel "<redacted-filename>".
         * Filenames are never hashed because even a hash can be used to confirm whether
         * a specific known file was present on the device.
         */
        fun redactFilename(
            @Suppress("UNUSED_PARAMETER") raw: String,
        ): String = "<redacted-filename>"

        /**
         * Returns the fixed sentinel "<redacted-gps>".
         * GPS coordinates are sentinel-redacted; no derivative value is exposed.
         */
        fun redactGps(
            @Suppress("UNUSED_PARAMETER") lat: Double,
            @Suppress("UNUSED_PARAMETER") lon: Double,
        ): String = "<redacted-gps>"

        // ── Internal ──────────────────────────────────────────────────────────────

        private fun sha256hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
