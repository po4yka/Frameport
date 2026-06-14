package dev.po4yka.frameport.feature.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * DataStore [Serializer] for [ImportPreferences] using kotlinx-serialization JSON.
 *
 * JSON is chosen over protobuf to avoid the protobuf Gradle plugin (not configured in this
 * workspace). The file is stored in app-private storage; it is never transmitted.
 *
 * Corruption handling: [readFrom] throws [CorruptionException] so DataStore resets to
 * [defaultValue], preserving the app's ability to launch even after a bad write.
 */
internal object ImportPreferencesSerializer : Serializer<ImportPreferences> {
    private val json =
        Json {
            ignoreUnknownKeys = true // forward-compatible when new fields are added
            encodeDefaults = true // write all fields including those at default values
        }

    override val defaultValue: ImportPreferences = ImportPreferences()

    override suspend fun readFrom(input: InputStream): ImportPreferences =
        try {
            json.decodeFromString(
                ImportPreferences.serializer(),
                input.readBytes().decodeToString(),
            )
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot deserialize ImportPreferences", e)
        }

    override suspend fun writeTo(
        t: ImportPreferences,
        output: OutputStream,
    ) {
        output.write(
            json.encodeToString(ImportPreferences.serializer(), t).encodeToByteArray(),
        )
    }
}
