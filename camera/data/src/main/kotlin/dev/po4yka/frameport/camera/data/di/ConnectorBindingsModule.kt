package dev.po4yka.frameport.camera.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.po4yka.frameport.nativebridge.JniNativeFujiSdk
import dev.po4yka.frameport.nativebridge.NativeFujiSdk
import javax.inject.Singleton

/**
 * Provides the low-level [NativeFujiSdk] JNI implementation.
 *
 * [CameraWifiConnector] is already bound in :camera:wifi's [CameraWifiModule];
 * do NOT duplicate that binding here.
 *
 * Dispatcher qualifiers are provided by :app's [AppDispatchersModule].
 */
@Module
@InstallIn(SingletonComponent::class)
object ConnectorBindingsModule {
    /**
     * Provides the JNI bridge. [JniNativeFujiSdk] falls back to [NoOpNativeFujiSdk]
     * internally when the native library is unavailable.
     */
    @Provides
    @Singleton
    fun provideNativeFujiSdk(): NativeFujiSdk = JniNativeFujiSdk()
}
