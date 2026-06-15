package dev.po4yka.frameport.core.common

import java.security.MessageDigest

/**
 * Pure SHA-256 utility. No Android dependencies — safe to use from any JVM layer.
 *
 * Privacy rule: callers must pass only pre-consented identifiers. Never pass raw BLE MAC,
 * Wi-Fi SSID, BSSID, or full file paths containing user account names.
 */
object Sha256 {
    /**
     * Returns the lowercase hexadecimal SHA-256 digest of [input] encoded as UTF-8.
     *
     * This function is stateless and thread-safe; [MessageDigest] instances are obtained
     * fresh per call (they are not thread-safe if shared).
     */
    fun hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
