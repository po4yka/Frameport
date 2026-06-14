package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.camera.api.CameraWifiConnector
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.EndpointMetadata
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.common.di.IoDispatcher
import dev.po4yka.frameport.core.model.FrameportError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [CameraRepository].
 *
 * Session lifecycle:
 * Idle -> Connecting -> SessionReady (on success) | Failed (on error)
 *
 * fd ownership: [CameraWifiConnector.openBoundSocket] produces an [OwnedSocketHandle] whose fd has
 * already been dup'd by the Android side. Ownership of that fd transfers to [FujiNativeSdk.openWifiSession];
 * Rust closes its copy. The Android socket is managed separately by [CameraWifiConnector].
 * See docs/rust/fd-ownership.md and ADR-0002.
 *
 * TODO(M09): Wire the real fd-handoff path — requestCameraNetwork -> openBoundSocket ->
 *   OwnedSocketHandle.fd -> fujiNativeSdk.openWifiSession. Currently stubbed with a placeholder
 *   SessionId to keep the session-state machine exercisable end-to-end without a live camera.
 */
@Singleton
class CameraRepositoryImpl
    @Inject
    constructor(
        private val cameraWifiConnector: CameraWifiConnector,
        private val fujiNativeSdk: FujiNativeSdk,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : CameraRepository {
        private val _sessionState = MutableStateFlow<CameraSessionState>(CameraSessionState.Idle)
        override val sessionState: StateFlow<CameraSessionState> = _sessionState.asStateFlow()

        // cancel-safe: withContext propagates cancellation into network + native calls.
        override suspend fun openSession(credentials: CameraWifiCredentials): Result<SessionId> =
            withContext(ioDispatcher) {
                _sessionState.value = CameraSessionState.Connecting

                // TODO(M09): Replace stub with real fd-handoff:
                //   val networkResult = cameraWifiConnector.requestCameraNetwork(credentials)
                //   networkResult.fold(
                //     onSuccess = { handle ->
                //       val socketResult = cameraWifiConnector.openBoundSocket(handle, cameraEndpoint)
                //       socketResult.fold(
                //         onSuccess = { socketHandle ->
                //           fujiNativeSdk.openWifiSession(socketHandle.fd, EndpointMetadata(host, port))
                //         },
                //         onFailure = { ... }
                //       )
                //     },
                //     onFailure = { ... }
                //   )

                // Stub: return a deterministic placeholder session.
                val stubSessionId = SessionId(0L)
                _sessionState.value = CameraSessionState.SessionReady(stubSessionId)
                Result.success(stubSessionId)
            }

        // cancel-safe: single delegated suspend call; cleanup is best-effort.
        override suspend fun closeSession(sessionId: SessionId) {
            withContext(ioDispatcher) {
                try {
                    fujiNativeSdk.closeSession(sessionId)
                } finally {
                    _sessionState.value = CameraSessionState.Closed
                }
            }
        }
    }
