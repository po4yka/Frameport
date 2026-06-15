package dev.po4yka.frameport.feature.settings

import dev.po4yka.frameport.camera.api.CameraMediaFormat
import kotlinx.coroutines.flow.Flow

/**
 * Read/write interface for user import preferences.
 *
 * The concrete implementation is backed by a typed DataStore<ImportPreferences>.
 * ViewModels depend on this interface, never on the DataStore directly.
 *
 * All writes are suspend functions so callers can observe back-pressure from the I/O layer.
 */
interface SettingsRepository {
    /** Cold flow of the current [ImportPreferences]; replays the latest value to new collectors. */
    val preferences: Flow<ImportPreferences>

    /** Replace the entire preferences object atomically. */
    suspend fun update(prefs: ImportPreferences)

    /** Toggle [ImportPreferences.autoImportOnConnect]. */
    suspend fun setAutoImportOnConnect(enabled: Boolean)

    /** Update the set of formats to import (empty = all). */
    suspend fun setFormatFilter(formats: Set<CameraMediaFormat>)

    /** Update the import path template string. */
    suspend fun setImportPathTemplate(template: String)

    /** Toggle [ImportPreferences.preserveOriginalFilename]. */
    suspend fun setPreserveOriginalFilename(preserve: Boolean)
}
