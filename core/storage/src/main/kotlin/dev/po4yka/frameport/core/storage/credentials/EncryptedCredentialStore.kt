package dev.po4yka.frameport.core.storage.credentials

/**
 * Encrypted storage for camera Wi-Fi credentials.
 *
 * PRIVACY invariants (privacy-local-first.md):
 * - Credentials are encrypted at rest using the Android Keystore. The key never leaves
 *   the secure hardware enclave (or software-backed Keystore on devices without SE/TEE).
 * - No credential value is ever logged, included in diagnostics, or exposed via an
 *   outbound network call.
 * - [clear] must be called when the user removes the camera or resets connection settings.
 *
 * Implementation note: the current backing store is [EncryptedSharedPreferencesCredentialStore]
 * via [androidx.security.crypto.EncryptedSharedPreferences]. Jetpack Security is in maintenance
 * mode (no new features planned); a migration to direct Tink AES-GCM + Android Keystore may
 * follow once Tink's Android Keystore integration stabilises. The interface is kept minimal so
 * the migration is a one-class swap with no caller impact.
 *
 * All operations are synchronous because [EncryptedSharedPreferences] does not expose a
 * suspend / Flow API. Callers MUST invoke these methods from a background dispatcher
 * (e.g. [kotlinx.coroutines.Dispatchers.IO]).
 */
interface EncryptedCredentialStore {
    /**
     * Stores [value] under [key], replacing any existing value.
     * [key] and [value] must not be empty.
     */
    fun put(
        key: String,
        value: String,
    )

    /**
     * Returns the value stored under [key], or null if no value has been stored.
     */
    fun get(key: String): String?

    /**
     * Removes the value stored under [key]. No-op if [key] is not present.
     */
    fun remove(key: String)

    /**
     * Removes all values from the store. Call this when the user disconnects the camera
     * or clears app data from within the app.
     */
    fun clear()

    companion object {
        /**
         * Key constants used by the camera Wi-Fi credential subsystem.
         * Defined here so callers and implementations use the same string literals.
         */
        const val KEY_WIFI_SSID = "camera_wifi_ssid"
        const val KEY_WIFI_PASSPHRASE = "camera_wifi_passphrase"
    }
}
