package dev.po4yka.frameport.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that wires the DataStore and repository for :feature:settings.
 *
 * Installed in [SingletonComponent] so the DataStore is a process-wide singleton —
 * consistent with DataStore's own recommendation (one instance per file).
 *
 * File name: "import_preferences.json" — stored in app-private filesDir, never in external storage.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SettingsDiModule {
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    companion object {
        @Provides
        @Singleton
        fun provideImportPreferencesDataStore(
            @ApplicationContext context: Context,
        ): DataStore<ImportPreferences> =
            DataStoreFactory.create(
                serializer = ImportPreferencesSerializer,
                produceFile = { context.dataStoreFile("import_preferences.json") },
            )
    }
}
