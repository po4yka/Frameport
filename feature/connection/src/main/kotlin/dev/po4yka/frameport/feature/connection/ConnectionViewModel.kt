package dev.po4yka.frameport.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.domain.OpenCameraSessionUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── UI state ─────────────────────────────────────────────────────────────────

/**
 * Typed UI state for the connection screen.
 *
 * Long-lived UI state is modelled as a sealed hierarchy so the Composable can
 * exhaustively branch without string comparison.
 *
 * States:
 * - [Idle]              — No active connection attempt; SSID field is editable.
 * - [EnteringCredentials] — User is typing an SSID; Connect is enabled.
 * - [Connecting]        — [OpenCameraSessionUseCase] is in progress.
 * - [Connected]         — Session is open; shows session info.
 * - [Disconnecting]     — closeSession call is in flight.
 * - [Error]             — A typed [ConnectionError] describes what went wrong.
 */
sealed interface ConnectionUiState {
    data object Idle : ConnectionUiState

    data class EnteringCredentials(
        val ssid: String,
    ) : ConnectionUiState

    data class Connecting(
        val ssid: String,
    ) : ConnectionUiState

    data class Connected(
        val sessionId: SessionId,
        val ssid: String,
    ) : ConnectionUiState

    data object Disconnecting : ConnectionUiState

    data class Error(
        val connectionError: ConnectionError,
        /** SSID that triggered the error — allows retry without re-typing. */
        val ssid: String,
    ) : ConnectionUiState
}

/** One-shot navigation events emitted on viewModelScope via [SharedFlow]. */
sealed interface ConnectionNavEvent {
    data object Cancelled : ConnectionNavEvent
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class ConnectionViewModel
    @Inject
    constructor(
        private val openCameraSessionUseCase: OpenCameraSessionUseCase,
        private val cameraRepository: CameraRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Idle)
        val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

        private val _navEvents = MutableSharedFlow<ConnectionNavEvent>()
        val navEvents: SharedFlow<ConnectionNavEvent> = _navEvents.asSharedFlow()

        // ── Public API ────────────────────────────────────────────────────────

        fun onSsidChanged(ssid: String) {
            val trimmed = ssid.trim()
            _uiState.value =
                if (trimmed.isEmpty()) {
                    ConnectionUiState.Idle
                } else {
                    ConnectionUiState.EnteringCredentials(trimmed)
                }
        }

        fun connect(ssid: String) {
            val trimmedSsid = ssid.trim()
            if (trimmedSsid.isEmpty()) return
            val credentials = CameraWifiCredentials(ssid = trimmedSsid, passphrase = null)
            viewModelScope.launch {
                openCameraSessionUseCase(credentials).collect { sessionState ->
                    _uiState.value = sessionState.toConnectionUiState(trimmedSsid)
                }
            }
        }

        fun disconnect() {
            val current = _uiState.value
            if (current !is ConnectionUiState.Connected) return
            viewModelScope.launch {
                _uiState.value = ConnectionUiState.Disconnecting
                cameraRepository.closeSession(current.sessionId)
                _uiState.value = ConnectionUiState.Idle
                _navEvents.emit(ConnectionNavEvent.Cancelled)
            }
        }

        fun retry() {
            val current = _uiState.value
            if (current !is ConnectionUiState.Error) return
            connect(current.ssid)
        }

        fun cancel() {
            val current = _uiState.value
            viewModelScope.launch {
                if (current is ConnectionUiState.Connected) {
                    _uiState.value = ConnectionUiState.Disconnecting
                    cameraRepository.closeSession(current.sessionId)
                }
                _uiState.value = ConnectionUiState.Idle
                _navEvents.emit(ConnectionNavEvent.Cancelled)
            }
        }

        // ── Private helpers ───────────────────────────────────────────────────

        // cancel-safe: pure mapping; no suspension.
        private fun CameraSessionState.toConnectionUiState(ssid: String): ConnectionUiState =
            when (this) {
                CameraSessionState.Idle -> {
                    ConnectionUiState.Idle
                }

                CameraSessionState.Connecting -> {
                    ConnectionUiState.Connecting(ssid)
                }

                is CameraSessionState.SessionReady -> {
                    ConnectionUiState.Connected(sessionId, ssid)
                }

                is CameraSessionState.Failed -> {
                    ConnectionUiState.Error(
                        connectionError = mapToConnectionError(error),
                        ssid = ssid,
                    )
                }

                CameraSessionState.Closed -> {
                    ConnectionUiState.Idle
                }
            }
    }

// ─── Legacy action type kept for backward compatibility with existing test ─────

// The original placeholder test used CameraRepository directly and ConnectionAction.
// New tests use the proper ConnectionViewModel API above.
// These are kept to avoid breaking the old test file until it is fully replaced.

sealed interface ConnectionAction {
    data object StartScan : ConnectionAction

    data object ConnectManually : ConnectionAction

    data object Cancel : ConnectionAction
}
