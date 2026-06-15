package dev.po4yka.frameport.feature.settings

import androidx.datastore.core.DataStore
import dev.po4yka.frameport.camera.api.CameraMediaFormat
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * [SettingsRepository] backed by a typed [DataStore]<[ImportPreferences]>.
 *
 * The DataStore instance is provided by Hilt via [SettingsDiModule].
 * All mutations go through [DataStore.updateData] which is atomic and crash-safe.
 *
 * This class is internal to :feature:settings; callers depend on [SettingsRepository].
 */
internal class DataStoreSettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<ImportPreferences>,
    ) : SettingsRepository {
        override val preferences: Flow<ImportPreferences> = dataStore.data

        override suspend fun update(prefs: ImportPreferences) {
            dataStore.updateData { prefs }
        }

        override suspend fun setAutoImportOnConnect(enabled: Boolean) {
            dataStore.updateData { it.copy(autoImportOnConnect = enabled) }
        }

        override suspend fun setFormatFilter(formats: Set<CameraMediaFormat>) {
            dataStore.updateData { it.copy(formatFilter = formats) }
        }

        override suspend fun setImportPathTemplate(template: String) {
            dataStore.updateData { it.copy(importPathTemplate = template) }
        }

        override suspend fun setPreserveOriginalFilename(preserve: Boolean) {
            dataStore.updateData { it.copy(preserveOriginalFilename = preserve) }
        }
    }
