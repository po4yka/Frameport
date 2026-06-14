package dev.po4yka.frameport.core.storage.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.po4yka.frameport.core.storage.catalog.ImportCatalog
import dev.po4yka.frameport.core.storage.catalog.RoomImportCatalog
import dev.po4yka.frameport.core.storage.catalog.db.FrameportDatabase
import dev.po4yka.frameport.core.storage.catalog.db.ImportCatalogDao
import javax.inject.Singleton

/**
 * Hilt module providing the Room database, DAO, and [ImportCatalog] binding.
 * Installed in [SingletonComponent] — the database is a process-wide singleton.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {
    @Binds
    @Singleton
    abstract fun bindImportCatalog(impl: RoomImportCatalog): ImportCatalog

    companion object {
        @Provides
        @Singleton
        fun provideFrameportDatabase(
            @ApplicationContext context: Context,
        ): FrameportDatabase =
            Room
                .databaseBuilder(
                    context,
                    FrameportDatabase::class.java,
                    "frameport.db",
                )
                // The import catalog is a local-only, reconstructible cache (it mirrors what is
                // already in MediaStore). For pre-1.0 schema changes we drop and rebuild rather
                // than ship migrations that could crash on upgrade. Replace with explicit
                // migrations once the schema stabilizes for release.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        @Provides
        @Singleton
        fun provideImportCatalogDao(database: FrameportDatabase): ImportCatalogDao = database.importCatalogDao()
    }
}
