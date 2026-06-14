package dev.po4yka.frameport.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.domain.BleAssistedConnectUseCase
import dev.po4yka.frameport.camera.domain.BleHandoffState
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
 * - [Idle]                   — No active connection attempt; SSID field is editable.
 * - [EnteringCredentials]    — User is typing an SSID; Connect is enabled.
 * - [Connecting]             — [OpenCameraSessionUseCase] is in progress (manual path).
 * - [ConnectingViaBleHandoff] — [BleAssistedConnectUseCase] is in progress (BLE handoff path).
 * - [Connected]              — Session is open; shows session info.
 * - [Disconnecting]          — closeSession call is in flight.
 * - [Error]                  — A typed [ConnectionError] describes what went wrong.
 */
sealed interface ConnectionUiState {
    data object Idle : ConnectionUiState

    data class EnteringCredentials(
        val ssid: String,
    ) : ConnectionUiState

    data class Connecting(
        val ssid: String,
    ) : ConnectionUiState

    /**
     * BLE-assisted Wi-Fi handoff is in progress.
     *
     * [stepLabel] is a safe, non-PII human-readable description of the current
     * handoff step (e.g. "Scanning for camera", "Connecting via Bluetooth",
     * "Joining camera network"). It is derived from [BleHandoffState] by the ViewModel
     * and never contains an SSID, passphrase, BLE MAC address, or raw bytes.
     */
    data class ConnectingViaBleHandoff(
        val stepLabel: String,
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
        private val bleAssistedConnectUseCase: BleAssistedConnectUseCase,
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

        /**
         * Initiates the BLE-assisted Wi-Fi handoff flow.
         *
         * Emits typed [ConnectionUiState.ConnectingViaBleHandoff] updates as the handoff
         * progresses through scanning, GATT connect, credential read, and network join phases.
         * On success, transitions to [ConnectionUiState.Connected] (ssid obtained from BLE).
         * On failure, transitions to [ConnectionUiState.Error] with an appropriate [ConnectionError].
         *
         * PRIVACY contract: no SSID, passphrase, BLE MAC address, or raw bytes are ever
         * passed to Timber or any logging call from this method.
         *
         * cancel-safe: [BleAssistedConnectUseCase.invoke] returns a cold Flow; cancelling
         * the viewModelScope job stops the Flow at the next suspension point cleanly.
         */
        fun startBleHandoff() {
            val current = _uiState.value
            // Ignore if already connecting or connected to avoid duplicate flows.
            if (current is ConnectionUiState.Connecting ||
                current is ConnectionUiState.ConnectingViaBleHandoff ||
                current is ConnectionUiState.Connected
            ) {
                return
            }

            viewModelScope.launch {
                bleAssistedConnectUseCase().collect { handoffState ->
                    _uiState.value = handoffState.toConnectionUiState()
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

        /**
         * Maps a [BleHandoffState] to a [ConnectionUiState].
         *
         * cancel-safe: pure mapping; no suspension.
         *
         * PRIVACY: [BleHandoffState.RequestingNetwork.ssid] and
         * [BleHandoffState.Connected.ssid] carry the network name for display purposes.
         * They are NOT logged here — only surfaced to the Composable for display.
         * The passphrase is never present in any [BleHandoffState] variant; it is consumed
         * and discarded inside [BleAssistedConnectUseCase].
         */
        private fun BleHandoffState.toConnectionUiState(): ConnectionUiState =
            when (this) {
                BleHandoffState.Scanning -> {
                    ConnectionUiState.ConnectingViaBleHandoff(stepLabel = "Scanning for camera…")
                }

                is BleHandoffState.CameraFound -> {
                    ConnectionUiState.ConnectingViaBleHandoff(stepLabel = "Camera found — connecting via Bluetooth…")
                }

                BleHandoffState.ObtainingCredentials -> {
                    ConnectionUiState.ConnectingViaBleHandoff(stepLabel = "Obtaining Wi-Fi credentials from camera…")
                }

                is BleHandoffState.RequestingNetwork -> {
                    ConnectionUiState.ConnectingViaBleHandoff(stepLabel = "Joining camera Wi-Fi network…")
                }

                is BleHandoffState.Connected -> {
                    // ssid is the camera network name; safe to surface for display.
                    ConnectionUiState.Connected(
                        // Session ID is not yet available at this state — use a sentinel.
                        // The PTP-IP session open (OpenCameraSessionUseCase) is a subsequent step.
                        // TODO(m14): wire through the PTP-IP session open after BLE handoff completes.
                        sessionId = SessionId(-1L),
                        ssid = ssid,
                    )
                }

                is BleHandoffState.Failed -> {
                    // reason is already PII-redacted by BleAssistedConnectUseCase.
                    ConnectionUiState.Error(
                        connectionError = ConnectionError.Unknown(reason),
                        ssid = "",
                    )
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
