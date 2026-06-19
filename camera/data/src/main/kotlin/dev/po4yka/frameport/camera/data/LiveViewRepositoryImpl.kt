package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.LiveViewRepository
import dev.po4yka.frameport.camera.api.SessionId
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [LiveViewRepository].
 *
 * Wraps [FujiNativeSdk.liveViewFrames] so the domain layer ([LiveViewUseCase])
 * never holds a direct reference to the JNI adapter.
 *
 * All JNI access, fd lifecycle, and backpressure policy live in [FujiNativeSdkAdapter].
 * This class is a thin delegation boundary.
 *
 * See [LiveViewRepository] for the full fd-ownership and backpressure contract.
 */
@Singleton
class LiveViewRepositoryImpl
    @Inject
    constructor(
        private val fujiNativeSdk: FujiNativeSdk,
    ) : LiveViewRepository {
        // cancel-safe: delegates to FujiNativeSdk.liveViewFrames which is a callbackFlow with
        // awaitClose. Cancellation triggers awaitClose which stops the Rust read loop cleanly.
        override fun liveViewFrames(
            sessionId: SessionId,
            liveViewFd: Int,
        ): Flow<ByteArray> = fujiNativeSdk.liveViewFrames(sessionId = sessionId, liveViewFd = liveViewFd)
    }
