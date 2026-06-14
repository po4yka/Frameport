package dev.po4yka.frameport.feature.settings

import dev.po4yka.frameport.camera.api.CameraMediaFormat
import kotlinx.serialization.Serializable

/**
 * User-configurable import preferences persisted via DataStore.
 *
 * All fields have safe defaults so the store can be read before the user ever touches Settings.
 *
 * [autoImportOnConnect] — when true, import begins as soon as a camera session is established.
 * [formatFilter]        — set of [CameraMediaFormat] values to import; empty = import all.
 * [importPathTemplate]  — relative path template under the device's DCIM root.
 *                         Supported placeholder: {date} (ISO-8601 date of import, not capture date).
 *
 * PRIVACY: this type is stored in app-private DataStore only; never transmitted.
 */
@Serializable
data class ImportPreferences(
    val autoImportOnConnect: Boolean = false,
    val formatFilter: Set<CameraMediaFormat> = emptySet(),
    val importPathTemplate: String = "DCIM/Frameport/{date}",
)
