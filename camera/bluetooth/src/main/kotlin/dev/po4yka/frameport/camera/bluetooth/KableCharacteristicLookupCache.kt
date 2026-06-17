package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.Characteristic
import com.juul.kable.DiscoveredService
import com.juul.kable.characteristicOf
import dev.po4yka.frameport.camera.api.CharacteristicId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal class KableCharacteristicLookupCache(
    private val services: () -> List<DiscoveredService>?,
) {
    private val characteristicsById = mutableMapOf<CharacteristicId, Characteristic>()

    fun characteristicFor(characteristicId: CharacteristicId): Characteristic =
        characteristicsById.getOrPut(characteristicId) {
            val characteristicUuid = Uuid.parse(characteristicId.value)
            val serviceUuid =
                services()
                    ?.firstOrNull { service ->
                        service.characteristics.any { it.characteristicUuid == characteristicUuid }
                    }?.serviceUuid
                    ?: throw BleTransportException.ServiceNotFound(characteristicId)
            characteristicOf(serviceUuid, characteristicUuid)
        }
}
