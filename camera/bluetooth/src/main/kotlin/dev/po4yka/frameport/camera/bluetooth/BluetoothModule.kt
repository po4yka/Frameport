package dev.po4yka.frameport.camera.bluetooth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.po4yka.frameport.camera.api.FujiBleClient
import javax.inject.Singleton

/**
 * Hilt module for :camera:bluetooth.
 *
 * Binds [AndroidFujiBleClient] as the singleton [FujiBleClient].
 *
 * IMPORTANT: When this module is active, the [dev.po4yka.frameport.di.CameraBindingsModule]
 * in :app must NOT also provide [FujiBleClient] — doing so causes a Hilt duplicate-binding
 * error. The [dev.po4yka.frameport.di.CameraBindingsModule.providesFujiBleClient] provider
 * must be removed from :app once this module is in place.
 *
 * The [GattTransport] and [BleScanner] bindings (real Android implementations) are also
 * declared here. The fake implementations for tests live in test source sets only.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BluetoothModule {
    /**
     * Bind the real [AndroidFujiBleClient] as [FujiBleClient].
     *
     * [AndroidFujiBleClient] is @Singleton and @Inject constructor — Hilt resolves
     * its [BleScanner], [GattTransport], and [@IoDispatcher] dependencies automatically.
     */
    @Binds
    @Singleton
    abstract fun bindFujiBleClient(impl: AndroidFujiBleClient): FujiBleClient

    /**
     * Bind the real Android BLE scanner implementation.
     *
     * [AndroidBleScanner] wraps Kable Scanner with a manufacturer-ID scan filter (0x04D8) applied at the Android layer.
     */
    @Binds
    @Singleton
    abstract fun bindBleScanner(impl: AndroidBleScanner): BleScanner

    /**
     * Bind the real Android GATT transport implementation.
     *
     * [AndroidGattTransport] wraps Kable Peripheral. All GATT operations are serialized by the [AndroidFujiBleClient] actor.
     */
    @Binds
    @Singleton
    abstract fun bindGattTransport(impl: AndroidGattTransport): GattTransport

    @Binds
    @Singleton
    abstract fun bindKableAdvertisementSource(impl: DefaultKableAdvertisementSource): KableAdvertisementSource

    @Binds
    @Singleton
    abstract fun bindKablePeripheralFactory(impl: DefaultKablePeripheralFactory): KablePeripheralFactory
}
