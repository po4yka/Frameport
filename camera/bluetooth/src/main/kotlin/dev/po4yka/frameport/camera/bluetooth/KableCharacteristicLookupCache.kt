package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.Characteristic
import com.juul.kable.DiscoveredService
import com.juul.kable.characteristicOf
import dev.po4yka.frameport.camera.api.CharacteristicId
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal class KableCharacteristicLookupCache(
    private val services: () -> List<DiscoveredService>?,
) {
    // ConcurrentHashMap.computeIfAbsent provides atomic read-compute-write semantics,
    // making the cache safe when accessed from both the actor coroutine and the
    // notification collector coroutine concurrently.
    private val characteristicsById = ConcurrentHashMap<CharacteristicId, Characteristic>()

    fun characteristicFor(characteristicId: CharacteristicId): Characteristic =
        characteristicsById.computeIfAbsent(characteristicId) { id ->
            val characteristicUuid = Uuid.parse(id.value)
            val serviceUuid =
                services()
                    ?.firstOrNull { service ->
                        service.characteristics.any { it.characteristicUuid == characteristicUuid }
                    }?.serviceUuid
                    ?: throw BleTransportException.ServiceNotFound(id)
            characteristicOf(serviceUuid, characteristicUuid)
        }
}
