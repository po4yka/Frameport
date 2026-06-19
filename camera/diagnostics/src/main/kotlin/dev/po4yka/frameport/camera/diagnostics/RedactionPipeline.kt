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
 *  - Raw device serial numbers, MAC addresses, SSIDs, GPS coordinates, filenames, and internal fd handles
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
         * Best-effort sanitizer for untrusted diagnostic text at ingestion boundaries.
         *
         * This is intentionally conservative: it preserves category-level wording while
         * replacing values that are private by policy before they reach the timeline,
         * logs, or exported bundles.
         */
        fun redactDiagnosticText(raw: String): String {
            var sanitized = raw
            replacementRules.forEach { (pattern, replacement) ->
                sanitized = pattern.replace(sanitized, replacement)
            }
            return sanitized
        }

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

        private companion object {
            private val replacementRules: List<Pair<Regex, String>> =
                listOf(
                    Regex("""(?i)\b(content|file)://[^\s,;]+""") to "<redacted-uri>",
                    Regex("""(?i)(\b(?:passphrase|password|secret|token|pairing[-_ ]?key)\b\s*[:=]\s*)[^\s,;]+""") to
                        "\$1<redacted-secret>",
                    // M-1 (DOCUMENTED CHOICE — keyworded rule retained):
                    // This rule matches serial numbers only when preceded by a recognised
                    // keyword (serial/serialNumber/cameraSerial followed by := or whitespace).
                    // A bare serial in free-form text is NOT caught here by design: a pattern
                    // broad enough to catch any uppercase alphanumeric token would redact
                    // category-level wording (e.g. "Transfer", "Protocol") and destroy
                    // diagnostic legibility.
                    //
                    // CONTRACT: every call site that produces a raw camera serial MUST pass it
                    // through redactSerial() before embedding it in a message or metadata value.
                    // The keyworded rule here is a last-resort catch for structured key=value
                    // log lines that slip through; it is not a substitute for explicit pre-hashing.
                    Regex("""(?i)(\b(?:serial|serialNumber|cameraSerial)\b\s*[:=]\s*)[A-Z0-9][A-Z0-9_-]{3,}""") to
                        "\$1<redacted-serial>",
                    Regex("""(?i)(\bssid\b\s*[:=]\s*)[^,;]+""") to "\$1<redacted-ssid>",
                    Regex("""(?i)(\b(?:fd|socketFd|usbFd|liveViewFd|eventFd|dupFd)\b\s*[:=]\s*)-?\d+""") to
                        "\$1<redacted-fd>",
                    Regex("""\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\b""") to "<redacted-mac>",
                    // M-2: consume optional :port suffix so "192.168.0.1:15740" is fully redacted.
                    Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b""") to "<redacted-ip>",
                    Regex("""[-+]?\d{1,2}\.\d{3,}\s*,\s*[-+]?\d{1,3}\.\d{3,}""") to "<redacted-gps>",
                    Regex("""(?i)\b\S+\.(?:raf|jpe?g|mov|mp4|heif|hif|dng)\b""") to "<redacted-filename>",
                    Regex("""(?:/[^/\s,;]+){2,}""") to "<redacted-path>",
                )
        }
    }
