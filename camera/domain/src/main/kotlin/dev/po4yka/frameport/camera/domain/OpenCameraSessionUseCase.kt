package dev.po4yka.frameport.camera.domain

import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.common.di.IoDispatcher
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.model.FrameportErrorException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/**
 * Opens a camera session by delegating to [CameraRepository].
 *
 * Emits [CameraSessionState.Connecting] immediately, then either
 * [CameraSessionState.SessionReady] on success or [CameraSessionState.Failed] on error.
 * Runs on [ioDispatcher] via [flowOn]; the collector's coroutine context is unaffected.
 */
class OpenCameraSessionUseCase
    @Inject
    constructor(
        private val cameraRepository: CameraRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        // cancel-safe: inner suspend calls run in flowOn(ioDispatcher) and are independently cancellable; flow builder is cooperative.
        operator fun invoke(credentials: CameraWifiCredentials): Flow<CameraSessionState> =
            flow {
                emit(CameraSessionState.Connecting)
                val result: Result<SessionId> = cameraRepository.openSession(credentials)
                result.fold(
                    onSuccess = { sessionId -> emit(CameraSessionState.SessionReady(sessionId)) },
                    onFailure = { throwable ->
                        val error: FrameportError =
                            (throwable as? FrameportErrorException)?.error
                                ?: FrameportError.Unknown(
                                    throwable.message ?: "openSession failed",
                                )
                        emit(CameraSessionState.Failed(error))
                    },
                )
            }.flowOn(ioDispatcher)
    }
