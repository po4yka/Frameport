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
import dev.po4yka.frameport.core.storage.catalog.db.FrameportMigrations
import dev.po4yka.frameport.core.storage.catalog.db.ImportCatalogDao
import dev.po4yka.frameport.core.storage.profile.db.CameraProfileDao
import dev.po4yka.frameport.core.storage.session.ExitReasonDedupStore
import dev.po4yka.frameport.core.storage.session.RoomExitReasonDedupStore
import dev.po4yka.frameport.core.storage.session.RoomSessionProgressStore
import dev.po4yka.frameport.core.storage.session.SessionProgressStore
import dev.po4yka.frameport.core.storage.session.db.ExitReasonDao
import dev.po4yka.frameport.core.storage.session.db.SessionProgressDao
import dev.po4yka.frameport.core.storage.timeline.LocalTimelineStore
import dev.po4yka.frameport.core.storage.timeline.RoomLocalTimelineStore
import javax.inject.Singleton

/**
 * Hilt module providing the Room database, DAOs, and store bindings.
 * Installed in [SingletonComponent] — the database is a process-wide singleton.
 *
 * Version notes:
 *   v2 — M10: added [SessionProgressDao] / [ExitReasonDao] providers and
 *             [SessionProgressStore] / [ExitReasonDedupStore] bindings.
 *   v3 — M18: added [FrameportMigrations.MIGRATION_1_2] to cover devices upgrading from v1.
 *             Replaced destructive-migration fallback with explicit [FrameportMigrations.MIGRATION_2_3].
 *             Added [CameraProfileDao] provider and [LocalTimelineStore] binding.
 *             Full v1→v2→v3 migration chain is now in place; no destructive fallback needed.
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

    @Binds
    @Singleton
    abstract fun bindLocalTimelineStore(impl: RoomLocalTimelineStore): LocalTimelineStore

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
                ).addMigrations(
                    FrameportMigrations.MIGRATION_1_2,
                    FrameportMigrations.MIGRATION_2_3,
                ).build()

        @Provides
        @Singleton
        fun provideImportCatalogDao(database: FrameportDatabase): ImportCatalogDao = database.importCatalogDao()

        @Provides
        @Singleton
        fun provideSessionProgressDao(database: FrameportDatabase): SessionProgressDao = database.sessionProgressDao()

        @Provides
        @Singleton
        fun provideExitReasonDao(database: FrameportDatabase): ExitReasonDao = database.exitReasonDao()

        @Provides
        @Singleton
        fun provideCameraProfileDao(database: FrameportDatabase): CameraProfileDao = database.cameraProfileDao()
    }
}
