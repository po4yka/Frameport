package dev.po4yka.frameport.camera.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.camera.api.DiagnosticsRepository
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.MediaRepository
import dev.po4yka.frameport.camera.api.TransferRepository
import dev.po4yka.frameport.camera.data.CameraRepositoryImpl
import dev.po4yka.frameport.camera.data.DiagnosticsRepositoryImpl
import dev.po4yka.frameport.camera.data.FujiNativeSdkAdapter
import dev.po4yka.frameport.camera.data.MediaRepositoryImpl
import dev.po4yka.frameport.camera.data.TransferRepositoryImpl
import javax.inject.Singleton

/**
 * Binds domain-layer repository interfaces to their production implementations.
 * Installed in [SingletonComponent] — all bindings are singletons.
 *
 * [CameraWifiConnector] binding lives in :camera:wifi's [CameraWifiModule] (already present).
 * [NativeFujiSdk] (the JNI low-level interface) is provided by [ConnectorBindingsModule].
 * Dispatcher qualifiers are provided by :app's [AppDispatchersModule].
 */
@Module
@InstallIn(SingletonComponent::class)
interface CameraBindingsModule {
    @Binds
    @Singleton
    fun bindCameraRepository(impl: CameraRepositoryImpl): CameraRepository

    @Binds
    @Singleton
    fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository

    @Binds
    @Singleton
    fun bindTransferRepository(impl: TransferRepositoryImpl): TransferRepository

    @Binds
    @Singleton
    fun bindDiagnosticsRepository(impl: DiagnosticsRepositoryImpl): DiagnosticsRepository

    @Binds
    @Singleton
    fun bindFujiNativeSdk(impl: FujiNativeSdkAdapter): FujiNativeSdk
}
