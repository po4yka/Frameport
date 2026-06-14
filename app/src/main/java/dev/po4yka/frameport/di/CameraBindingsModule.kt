package dev.po4yka.frameport.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.po4yka.frameport.camera.bluetooth.FujiBleClient
import dev.po4yka.frameport.camera.bluetooth.NoOpFujiBleClient
import dev.po4yka.frameport.camera.diagnostics.DiagnosticEventSink
import dev.po4yka.frameport.camera.diagnostics.NoOpDiagnosticEventSink
import dev.po4yka.frameport.camera.media.MediaImportWriter
import dev.po4yka.frameport.camera.media.NoOpMediaImportWriter
import dev.po4yka.frameport.camera.usb.CameraUsbConnector
import dev.po4yka.frameport.camera.usb.NoOpCameraUsbConnector
import javax.inject.Singleton

/**
 * App-level DI bindings for subsystem stubs that are not yet wired to real implementations.
 *
 * Camera repository bindings (CameraRepository, MediaRepository, TransferRepository,
 * DiagnosticsRepository, FujiNativeSdk) have moved to :camera:data's CameraBindingsModule
 * and ConnectorBindingsModule.
 *
 * NativeFujiSdk (the low-level JNI interface from :native:fuji-rust-android) has moved to
 * :camera:data's ConnectorBindingsModule.
 *
 * Dispatcher bindings remain in AppDispatchersModule.
 */
@Module
@InstallIn(SingletonComponent::class)
object CameraBindingsModule {
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
}
