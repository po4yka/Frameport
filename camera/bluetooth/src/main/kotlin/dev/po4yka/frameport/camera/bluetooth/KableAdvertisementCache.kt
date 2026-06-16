package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.Advertisement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local bridge from Frameport's stable [dev.po4yka.frameport.camera.api.BleCameraRef.id]
 * to the Kable advertisement needed to create a Peripheral.
 */
@Singleton
internal class KableAdvertisementCache
    @Inject
    constructor() {
        private val advertisementsById = mutableMapOf<String, Advertisement>()

        @Synchronized
        fun remember(advertisement: Advertisement): String {
            val id = advertisement.identifier
            advertisementsById[id] = advertisement
            return id
        }

        @Synchronized
        fun advertisementFor(id: String): Advertisement? = advertisementsById[id]

        @Synchronized
        fun clear() {
            advertisementsById.clear()
        }
    }
