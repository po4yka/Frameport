package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.Characteristic
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredDescriptor
import com.juul.kable.DiscoveredService
import com.juul.kable.ExperimentalApi
import dev.po4yka.frameport.camera.api.CharacteristicId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalApi::class, ExperimentalUuidApi::class)
class KableCharacteristicLookupCacheTest {
    @Test
    fun characteristicLookupIsCachedAfterFirstServiceScan() {
        val serviceUuid = Uuid.parse(BleConstants.SERVICE_CAMERA_INFORMATION)
        val characteristicUuid = Uuid.parse(BleConstants.CHR_CAMERA_SSID_NAME_STRING)
        val services =
            listOf(
                FakeDiscoveredService(
                    serviceUuid = serviceUuid,
                    characteristics =
                        listOf(
                            FakeDiscoveredCharacteristic(
                                serviceUuid = serviceUuid,
                                characteristicUuid = characteristicUuid,
                            ),
                        ),
                ),
            )
        var serviceProviderCalls = 0
        val cache =
            KableCharacteristicLookupCache {
                serviceProviderCalls++
                services
            }
        val characteristicId = CharacteristicId(BleConstants.CHR_CAMERA_SSID_NAME_STRING)

        val first = cache.characteristicFor(characteristicId)
        val second = cache.characteristicFor(characteristicId)

        assertSame(first, second)
        assertEquals(1, serviceProviderCalls)
    }

    @Test
    fun missingCharacteristicIsNotCachedAsSuccess() {
        var serviceProviderCalls = 0
        val cache =
            KableCharacteristicLookupCache {
                serviceProviderCalls++
                emptyList()
            }
        val characteristicId = CharacteristicId(BleConstants.CHR_CAMERA_SSID_NAME_STRING)

        repeat(2) {
            val result =
                runCatching {
                    cache.characteristicFor(characteristicId)
                }
            assertEquals(
                BleTransportException.ServiceNotFound::class,
                result.exceptionOrNull()!!::class,
            )
            assertEquals(
                characteristicId,
                (result.exceptionOrNull() as BleTransportException.ServiceNotFound).characteristicId,
            )
        }

        assertEquals(2, serviceProviderCalls)
    }

    private class FakeDiscoveredService(
        override val serviceUuid: Uuid,
        override val characteristics: List<DiscoveredCharacteristic>,
    ) : DiscoveredService

    private class FakeDiscoveredCharacteristic(
        override val serviceUuid: Uuid,
        override val characteristicUuid: Uuid,
    ) : DiscoveredCharacteristic {
        override val descriptors: List<DiscoveredDescriptor> = emptyList()
        override val properties: Characteristic.Properties = Characteristic.Properties(0)
    }
}
