package dev.po4yka.frameport.camera.domain

import dev.po4yka.frameport.camera.api.CameraMediaObject
import dev.po4yka.frameport.camera.api.MediaRepository
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Fetches the list of media objects for an active camera session.
 *
 * Delegates to [MediaRepository.listMedia] on [ioDispatcher].
 */
class ListMediaUseCase
    @Inject
    constructor(
        private val mediaRepository: MediaRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        // cancel-safe: single withContext(ioDispatcher) suspend call; cancellation propagates into the block.
        suspend operator fun invoke(sessionId: SessionId): Result<List<CameraMediaObject>> =
            withContext(ioDispatcher) { mediaRepository.listMedia(sessionId) }
    }
