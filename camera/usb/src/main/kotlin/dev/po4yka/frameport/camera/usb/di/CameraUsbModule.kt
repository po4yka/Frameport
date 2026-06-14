package dev.po4yka.frameport.camera.usb.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.po4yka.frameport.camera.api.CameraUsbConnector
import dev.po4yka.frameport.camera.usb.AndroidCameraUsbConnector
import javax.inject.Singleton

/**
 * Hilt module binding [CameraUsbConnector] to [AndroidCameraUsbConnector].
 *
 * Mirrors the pattern in :camera:wifi's [CameraWifiModule] — a single @Binds
 * in an interface module installed into SingletonComponent.
 *
 * [AndroidCameraUsbConnector] is @Singleton: one instance per application lifetime.
 * The singleton registers a BroadcastReceiver in its init block and holds UsbDevice
 * and connection state; multiple instances would register duplicate receivers.
 *
 * [FujiNativeSdk] is bound elsewhere (:camera:data ConnectorBindingsModule) and
 * injected into [AndroidCameraUsbConnector] by Hilt.
 */
@Module
@InstallIn(SingletonComponent::class)
interface CameraUsbModule {
    @Binds
    @Singleton
    fun bindCameraUsbConnector(impl: AndroidCameraUsbConnector): CameraUsbConnector
}
