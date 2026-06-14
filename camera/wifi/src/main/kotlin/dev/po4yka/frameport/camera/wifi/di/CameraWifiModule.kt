package dev.po4yka.frameport.camera.wifi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.po4yka.frameport.camera.api.CameraWifiConnector
import dev.po4yka.frameport.camera.wifi.CameraWifiConnectorImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface CameraWifiModule {
    @Binds
    @Singleton
    fun bindCameraWifiConnector(impl: CameraWifiConnectorImpl): CameraWifiConnector
}
