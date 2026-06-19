package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.RemoteCaptureRepository
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.ShutterAction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [RemoteCaptureRepository].
 *
 * Wraps [FujiNativeSdk.remoteShutter] so the domain layer ([RemoteCaptureUseCase])
 * never holds a direct reference to the JNI adapter.
 *
 * All JNI access and dispatcher management live in [FujiNativeSdkAdapter].
 * This class is a thin delegation boundary.
 *
 * TODO(M16): when the real fuji-ffi JNI entry point for RemoteSession is wired,
 *   the change is contained to [FujiNativeSdkAdapter.remoteShutter] — this impl
 *   and [RemoteCaptureRepository] do not need to change.
 */
@Singleton
class RemoteCaptureRepositoryImpl
    @Inject
    constructor(
        private val fujiNativeSdk: FujiNativeSdk,
    ) : RemoteCaptureRepository {
        // cancel-safe: single withContext call delegated to JNI stub; no shared mutable
        // state mutated after cancellation.
        override suspend fun remoteShutter(
            sessionId: SessionId,
            action: ShutterAction,
        ): Result<Unit> = fujiNativeSdk.remoteShutter(sessionId = sessionId, action = action)
    }
