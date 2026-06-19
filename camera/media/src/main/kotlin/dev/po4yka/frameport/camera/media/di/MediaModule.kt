package dev.po4yka.frameport.camera.media.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.po4yka.frameport.camera.media.AndroidMediaStoreGateway
import dev.po4yka.frameport.camera.media.MediaStoreGateway
import dev.po4yka.frameport.camera.media.MediaStoreWriter
import dev.po4yka.frameport.camera.media.MediaStoreWriterImpl
import java.time.Clock
import javax.inject.Singleton

/**
 * Hilt bindings for :camera:media production implementations.
 *
 * [AndroidMediaStoreGateway] requires [@ApplicationContext] which Hilt injects automatically.
 * [MediaStoreWriterImpl] requires [@ApplicationContext], [FujiNativeSdk], [@IoDispatcher],
 * [ImportCatalog], [FujiFormatMimeMapper], [MediaStoreGateway], and [Clock] — all provided
 * elsewhere in the Hilt graph ([CameraBindingsModule], [AppDispatchersModule], [StorageModule])
 * or directly below.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {
    @Binds
    @Singleton
    abstract fun bindMediaStoreGateway(impl: AndroidMediaStoreGateway): MediaStoreGateway

    @Binds
    @Singleton
    abstract fun bindMediaStoreWriter(impl: MediaStoreWriterImpl): MediaStoreWriter

    companion object {
        /**
         * Provides the system UTC clock for production use.
         * Tests replace this binding by passing a [Clock.fixed] directly to [MediaStoreWriterImpl].
         */
        @Provides
        @Singleton
        fun provideClock(): Clock = Clock.systemUTC()
    }
}
