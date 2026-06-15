package dev.po4yka.frameport.feature.settings

import dev.po4yka.frameport.camera.api.CameraMediaFormat
import kotlinx.serialization.Serializable

/**
 * User-configurable import preferences persisted via DataStore.
 *
 * All fields have safe defaults so the store can be read before the user ever touches Settings.
 *
 * [autoImportOnConnect]      — when true, import begins as soon as a camera session is established.
 * [formatFilter]             — set of [CameraMediaFormat] values to import; empty = import all.
 * [importPathTemplate]       — relative path template under the device's DCIM root.
 *                              Supported placeholders: {date} (ISO-8601 import date), {model} (camera model name).
 * [preserveOriginalFilename] — when true, the import pipeline attempts to preserve the camera's
 *                              original filename in the MediaStore display name. When false, the
 *                              pipeline assigns a locally-generated name based on the import
 *                              timestamp and format. Defaults to false (privacy-safer default:
 *                              camera filenames can encode shooting sequence information).
 *
 * PRIVACY: this type is stored in app-private DataStore only; never transmitted.
 *
 * M18: kept typed JSON DataStore per project decision (protobuf plugin intentionally not
 * configured); Proto DataStore deferred. New fields with defaults are backward-compatible
 * because the serializer sets ignoreUnknownKeys=true and encodeDefaults=true.
 */
@Serializable
data class ImportPreferences(
    val autoImportOnConnect: Boolean = false,
    val formatFilter: Set<CameraMediaFormat> = emptySet(),
    val importPathTemplate: String = "DCIM/Frameport/{date}/{model}",
    val preserveOriginalFilename: Boolean = false,
)
