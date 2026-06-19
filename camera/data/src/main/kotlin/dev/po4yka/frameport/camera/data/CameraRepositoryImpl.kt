package dev.po4yka.frameport.camera.data

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.camera.api.CameraWifiConnector
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.EndpointMetadata
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.common.di.IoDispatcher
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
 * Service start/stop (G4):
 * - On [openSession] success: fires [CameraSessionService] via [Context.startForegroundService].
 *   The service wires [CameraSessionController] which drives stopSelf() on terminal state.
 * - On [closeSession]: sets state to [CameraSessionState.Closed]; [CameraSessionController]
 *   detects the terminal state and calls stopSelf() on the service. The repository does NOT
 *   call stopService directly — the state machine drives the stop.
 * - ViewModels and Composables MUST NOT call startForegroundService / stopService directly.
 *
 * fd ownership: [CameraWifiConnector.openBoundSocket] produces an [OwnedSocketHandle] whose fd
 * has already been detached by the Android side. [FujiNativeSdk.openWifiSession] borrows and
 * duplicates that fd via the native bridge; the camera data adapter closes the detached fd after
 * the bridge call returns. Rust closes only its own dup. See docs/rust/fd-ownership.md and ADR-0002.
 *
 * TODO(M09): Wire the real fd-handoff path — requestCameraNetwork -> openBoundSocket ->
 *   OwnedSocketHandle.fd -> fujiNativeSdk.openWifiSession. Currently stubbed with a placeholder
 *   SessionId to keep the session-state machine exercisable end-to-end without a live camera.
 */
@Singleton
class CameraRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
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
                // TODO(M09): Replace with the real session id returned by fujiNativeSdk.openWifiSession.
                val stubSessionId = SessionId(0L)
                _sessionState.value = CameraSessionState.SessionReady(stubSessionId)

                // Start the foreground service only when a real session id is available.
                // SessionId(0L) is a stub placeholder; native_close_session(0) returns
                // ERR_INVALID_SESSION, confirming 0 is not a valid handle. Once M09 wires
                // the real fd-handoff path, stubSessionId.value will be a non-zero Rust
                // session handle and the condition below will pass.
                // TODO(audit): remove the stub guard and always start FGS here once M09
                //   replaces SessionId(0L) with the real session id from openWifiSession.
                if (stubSessionId.value != 0L) {
                    val intent =
                        Intent(context, CameraSessionService::class.java).apply {
                            putExtra(CameraSessionService.EXTRA_SESSION_ID, stubSessionId.value)
                        }
                    context.startForegroundService(intent)
                }

                Result.success(stubSessionId)
            }

        // cancel-safe: single delegated suspend call; cleanup is best-effort.
        override suspend fun closeSession(sessionId: SessionId) {
            withContext(ioDispatcher) {
                try {
                    fujiNativeSdk.closeSession(sessionId)
                } finally {
                    // Setting Closed causes CameraSessionController to call stopSelf()
                    // on the service. Do NOT call context.stopService() here — the state
                    // machine is the single source of truth for stop timing.
                    _sessionState.value = CameraSessionState.Closed
                }
            }
        }
    }
