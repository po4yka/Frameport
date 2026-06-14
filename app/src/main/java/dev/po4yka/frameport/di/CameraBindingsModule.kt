package dev.po4yka.frameport.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

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
 * DiagnosticsRepository binding has moved to :camera:diagnostics's DiagnosticsModule.
 * DiagnosticEventSink (old sink) is superseded by DiagnosticCollector + DiagnosticTimeline
 * in :camera:diagnostics and is no longer provided here.
 *
 * Dispatcher bindings remain in AppDispatchersModule.
 *
 * FujiBleClient is provided by :camera:bluetooth BluetoothModule (@Binds AndroidFujiBleClient).
 * The NoOp provider that lived here has been removed to avoid a Hilt duplicate-binding error.
 *
 * CameraUsbConnector is bound by :camera:usb CameraUsbModule (@Binds AndroidCameraUsbConnector).
 * The NoOp provider that lived here has been removed (M17).
 */
@Module
@InstallIn(SingletonComponent::class)
object CameraBindingsModule
