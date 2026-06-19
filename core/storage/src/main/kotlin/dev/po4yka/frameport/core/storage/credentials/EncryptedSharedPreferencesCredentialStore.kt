package dev.po4yka.frameport.core.storage.credentials

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Keystore-backed implementation of [EncryptedCredentialStore].
 *
 * Uses [EncryptedSharedPreferences] from androidx.security:security-crypto, which wraps
 * an [android.content.SharedPreferences] file whose keys and values are encrypted with
 * AES-256-SIV (keys) and AES-256-GCM (values) respectively. The master key lives in the
 * Android Keystore — it never appears in plaintext in app storage.
 *
 * Threading: all [EncryptedSharedPreferences] operations are synchronous. Callers MUST
 * dispatch to [kotlinx.coroutines.Dispatchers.IO] before invoking any method on this class.
 * The Hilt binding is [Singleton]; the underlying [EncryptedSharedPreferences] instance is
 * created once and cached.
 *
 * Maintenance note: androidx.security:security-crypto is in maintenance mode. If the project
 * migrates to direct Tink AES-GCM + Android Keystore, replace this class while keeping the
 * [EncryptedCredentialStore] interface stable.
 *
 * PRIVACY invariants:
 * - No credential value is logged, included in diagnostics, or transmitted.
 * - The preferences file name is not user-identifiable.
 * - [clear] wipes all entries; it is called when the user disconnects the camera.
 */
@Singleton
class EncryptedSharedPreferencesCredentialStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : EncryptedCredentialStore {
        private val prefs by lazy {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        override fun put(
            key: String,
            value: String,
        ) {
            require(key.isNotEmpty()) { "Credential key must not be empty" }
            require(value.isNotEmpty()) { "Credential value must not be empty" }
            prefs.edit().putString(key, value).apply()
        }

        override fun get(key: String): String? = prefs.getString(key, null)

        override fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }

        override fun clear() {
            prefs.edit().clear().apply()
        }

        private companion object {
            // Not user-identifiable; internal to app-private storage.
            const val PREFS_FILE_NAME = "frameport_credentials"
        }
    }
