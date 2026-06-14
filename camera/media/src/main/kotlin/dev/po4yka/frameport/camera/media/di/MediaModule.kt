package dev.po4yka.frameport.camera.media.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.po4yka.frameport.camera.media.AndroidMediaStoreGateway
import dev.po4yka.frameport.camera.media.MediaStoreGateway
import dev.po4yka.frameport.camera.media.MediaStoreWriter
import dev.po4yka.frameport.camera.media.MediaStoreWriterImpl
import javax.inject.Singleton

/**
 * Hilt bindings for :camera:media production implementations.
 *
 * [AndroidMediaStoreGateway] requires [@ApplicationContext] which Hilt injects automatically.
 * [MediaStoreWriterImpl] requires [@ApplicationContext], [FujiNativeSdk], [@IoDispatcher],
 * [ImportCatalog], [FujiFormatMimeMapper], and [MediaStoreGateway] — all provided elsewhere
 * in the Hilt graph ([CameraBindingsModule], [AppDispatchersModule], [StorageModule]).
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
}
