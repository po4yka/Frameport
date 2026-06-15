package dev.po4yka.frameport.camera.domain

import dev.po4yka.frameport.camera.api.CameraProfileRepository
import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.common.di.IoDispatcher
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.model.FrameportErrorException
import dev.po4yka.frameport.core.model.TransportCapability
import dev.po4yka.frameport.core.model.toBitmask
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
 *
 * On success, a best-effort upsert of [CameraProfileRepository] is performed. A failure
 * there is suppressed via [runCatching] so that a profile-write error never causes the
 * caller to see a failed session.
 *
 * cameraModel and firmwareVersion are placeholders until GetDeviceInfo (PTP opcode 0x1001)
 * is wired in M19+. At that point this use-case should be extended to perform the device-info
 * query and pass real values instead of "unknown".
 */
class OpenCameraSessionUseCase
    @Inject
    constructor(
        private val cameraRepository: CameraRepository,
        private val cameraProfileRepository: CameraProfileRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        // cancel-safe: inner suspend calls run in flowOn(ioDispatcher) and are independently
        // cancellable; the flow builder is cooperative with coroutine cancellation.
        operator fun invoke(credentials: CameraWifiCredentials): Flow<CameraSessionState> =
            flow {
                emit(CameraSessionState.Connecting)
                val result: Result<SessionId> = cameraRepository.openSession(credentials)
                result.fold(
                    onSuccess = { sessionId ->
                        emit(CameraSessionState.SessionReady(sessionId))

                        // Best-effort profile upsert: a failure here must never break the
                        // session that was just successfully opened. cameraModel and
                        // firmwareVersion are placeholders until GetDeviceInfo is wired (M19+).
                        // rawSerialOrStableId is the SSID (the only stable-ish camera
                        // identifier available at session-open time in M18); it is hashed by
                        // the repository before persistence — the plaintext SSID never reaches
                        // the database. See privacy-local-first.md.
                        // cancel-safe: upsertOnSessionOpen is a Room suspend DAO call;
                        // cancellation propagates cleanly through withContext(ioDispatcher).
                        runCatching {
                            cameraProfileRepository.upsertOnSessionOpen(
                                sessionId = sessionId,
                                cameraModel = "unknown",
                                firmwareVersion = "unknown",
                                rawSerialOrStableId = credentials.ssid,
                                transportCapabilities = setOf(TransportCapability.WIFI).toBitmask(),
                                compatibilityFlags = 0L,
                            )
                        }
                    },
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
