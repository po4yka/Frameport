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
import dev.po4yka.frameport.core.storage.session.ExitReasonDedupStore
import dev.po4yka.frameport.core.storage.session.RoomExitReasonDedupStore
import dev.po4yka.frameport.core.storage.session.RoomSessionProgressStore
import dev.po4yka.frameport.core.storage.session.SessionProgressStore
import dev.po4yka.frameport.core.storage.session.db.ExitReasonDao
import dev.po4yka.frameport.core.storage.session.db.SessionProgressDao
import javax.inject.Singleton

/**
 * Hilt module providing the Room database, DAOs, and store bindings.
 * Installed in [SingletonComponent] — the database is a process-wide singleton.
 *
 * Version notes:
 *   v2 — M10: added [SessionProgressDao] / [ExitReasonDao] providers and
 *             [SessionProgressStore] / [ExitReasonDedupStore] bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {
    @Binds
    @Singleton
    abstract fun bindImportCatalog(impl: RoomImportCatalog): ImportCatalog

    @Binds
    @Singleton
    abstract fun bindSessionProgressStore(impl: RoomSessionProgressStore): SessionProgressStore

    @Binds
    @Singleton
    abstract fun bindExitReasonDedupStore(impl: RoomExitReasonDedupStore): ExitReasonDedupStore

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
                // The local catalog and session data are reconstructible caches. For pre-1.0
                // schema changes we drop and rebuild rather than ship migrations that could crash
                // on upgrade. Replace with explicit migrations once the schema stabilizes for
                // release.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        @Provides
        @Singleton
        fun provideImportCatalogDao(database: FrameportDatabase): ImportCatalogDao = database.importCatalogDao()

        @Provides
        @Singleton
        fun provideSessionProgressDao(database: FrameportDatabase): SessionProgressDao = database.sessionProgressDao()

        @Provides
        @Singleton
        fun provideExitReasonDao(database: FrameportDatabase): ExitReasonDao = database.exitReasonDao()
    }
}
