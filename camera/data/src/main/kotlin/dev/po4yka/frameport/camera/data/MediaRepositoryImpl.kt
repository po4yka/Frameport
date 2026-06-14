package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.CameraMediaObject
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.MediaRepository
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [MediaRepository].
 *
 * Delegates all calls to [FujiNativeSdk] on [ioDispatcher].
 */
@Singleton
class MediaRepositoryImpl
    @Inject
    constructor(
        private val fujiNativeSdk: FujiNativeSdk,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : MediaRepository {
        // cancel-safe: single withContext call delegated to FujiNativeSdk.listMedia.
        override suspend fun listMedia(sessionId: SessionId): Result<List<CameraMediaObject>> =
            withContext(ioDispatcher) { fujiNativeSdk.listMedia(sessionId) }

        // cancel-safe: single withContext call delegated to FujiNativeSdk.getThumbnail.
        override suspend fun getThumbnail(
            sessionId: SessionId,
            handle: CameraObjectHandle,
        ): Result<ByteArray> = withContext(ioDispatcher) { fujiNativeSdk.getThumbnail(sessionId, handle) }
    }
