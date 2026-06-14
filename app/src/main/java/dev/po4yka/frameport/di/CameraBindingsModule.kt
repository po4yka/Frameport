package dev.po4yka.frameport.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.po4yka.frameport.camera.api.CameraConnectionManager
import dev.po4yka.frameport.camera.api.CameraDiagnosticsRepository
import dev.po4yka.frameport.camera.api.CameraMediaRepository
import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.camera.bluetooth.FujiBleClient
import dev.po4yka.frameport.camera.bluetooth.NoOpFujiBleClient
import dev.po4yka.frameport.camera.data.NoOpCameraConnectionManager
import dev.po4yka.frameport.camera.data.NoOpCameraDiagnosticsRepository
import dev.po4yka.frameport.camera.data.NoOpCameraMediaRepository
import dev.po4yka.frameport.camera.data.NoOpCameraRepository
import dev.po4yka.frameport.camera.diagnostics.DiagnosticEventSink
import dev.po4yka.frameport.camera.diagnostics.NoOpDiagnosticEventSink
import dev.po4yka.frameport.camera.media.MediaImportWriter
import dev.po4yka.frameport.camera.media.NoOpMediaImportWriter
import dev.po4yka.frameport.camera.usb.CameraUsbConnector
import dev.po4yka.frameport.camera.usb.NoOpCameraUsbConnector
import dev.po4yka.frameport.nativebridge.JniNativeFujiSdk
import dev.po4yka.frameport.nativebridge.NativeFujiSdk
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CameraBindingsModule {
    @Provides
    @Singleton
    fun providesCameraRepository(): CameraRepository = NoOpCameraRepository()

    @Provides
    @Singleton
    fun providesCameraConnectionManager(): CameraConnectionManager = NoOpCameraConnectionManager()

    @Provides
    @Singleton
    fun providesCameraMediaRepository(): CameraMediaRepository = NoOpCameraMediaRepository()

    @Provides
    @Singleton
    fun providesCameraDiagnosticsRepository(): CameraDiagnosticsRepository = NoOpCameraDiagnosticsRepository()

    @Provides
    @Singleton
    fun providesFujiBleClient(): FujiBleClient = NoOpFujiBleClient()

    @Provides
    @Singleton
    fun providesCameraUsbConnector(): CameraUsbConnector = NoOpCameraUsbConnector()

    @Provides
    @Singleton
    fun providesMediaImportWriter(): MediaImportWriter = NoOpMediaImportWriter()

    @Provides
    @Singleton
    fun providesDiagnosticEventSink(): DiagnosticEventSink = NoOpDiagnosticEventSink()

    @Provides
    @Singleton
    fun providesNativeFujiSdk(): NativeFujiSdk = JniNativeFujiSdk()
}
