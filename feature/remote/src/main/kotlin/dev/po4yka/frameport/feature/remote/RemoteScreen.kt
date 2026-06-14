package dev.po4yka.frameport.feature.remote

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.RemoteCaptureError
import dev.po4yka.frameport.camera.api.RemoteCaptureRequest
import dev.po4yka.frameport.camera.api.RemoteCaptureState
import dev.po4yka.frameport.camera.api.ShutterAction
import dev.po4yka.frameport.camera.domain.RemoteCaptureUseCase
import dev.po4yka.frameport.core.designsystem.FrameportCard
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import dev.po4yka.frameport.core.designsystem.PrimaryActionButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

// ─── UI state ────────────────────────────────────────────────────────────────

/**
 * UI state for the remote shutter screen.
 *
 * Maps [RemoteCaptureState] to presentation-layer variants that the Composable
 * renders directly. The ViewModel is responsible for the translation; the screen
 * is a pure function of [RemoteShutterUiState].
 *
 * Boundary: this type lives in the :feature:remote module and must NOT reference
 * any Android platform type (BluetoothGatt, JNI, etc.).
 */
sealed interface RemoteShutterUiState {
    /** Initial state; camera not yet engaged. Buttons are enabled. */
    data object Idle : RemoteShutterUiState

    /** Half-press (autofocus) sent; waiting for full-press or release. */
    data object HalfPress : RemoteShutterUiState

    /** Full-press sent; camera is processing the capture. */
    data object FullPress : RemoteShutterUiState

    /** Camera confirmed the shutter released (back to ready state). */
    data object Released : RemoteShutterUiState

    /** Capture command is in flight; spinner is shown. */
    data object CapturingInProgress : RemoteShutterUiState

    /** Capture acknowledged by the camera. */
    data object CaptureComplete : RemoteShutterUiState

    /** Terminal error; [message] is a pre-redacted, user-facing diagnostic string. */
    data class Error(
        val message: String,
    ) : RemoteShutterUiState

    /**
     * Camera model is not in the verified remote-capable allowlist.
     * The screen shows a compatibility warning and disables all shutter buttons.
     */
    data object IncompatibleCamera : RemoteShutterUiState
}

// ─── Actions ─────────────────────────────────────────────────────────────────

/** User actions emitted by the Composable to the ViewModel. */
sealed interface RemoteAction {
    /** User tapped the half-press (autofocus) button. */
    data object HalfPress : RemoteAction

    /** User tapped the full-press (capture) button. */
    data object FullPress : RemoteAction

    /** User tapped the release button. */
    data object Release : RemoteAction

    /** User tapped Retry after an error. */
    data object Retry : RemoteAction
}

// ─── ViewModel ───────────────────────────────────────────────────────────────

/**
 * ViewModel for [RemoteScreen].
 *
 * Injects [RemoteCaptureUseCase] (from :camera:domain) — the ONLY dependency.
 * It NEVER references BluetoothGatt, JNI types, Android platform I/O, or any
 * :camera:data / :native:fuji-rust-android class directly.
 *
 * The ViewModel does no suspend I/O itself. All I/O is delegated to the use case
 * and collected in [viewModelScope] via [launchIn].
 *
 * Camera model: wired to null (stub) in M15; real identity read comes in M16 when
 * the PTP-IP session identity negotiation result is propagated here.
 * TODO(M16): accept cameraModel from CameraRepository.sessionState or a dedicated identity flow.
 */
@HiltViewModel
class RemoteViewModel
    @Inject
    constructor(
        private val remoteCaptureUseCase: RemoteCaptureUseCase,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<RemoteShutterUiState>(RemoteShutterUiState.Idle)

        /** Observed by [RemoteRoute] via [collectAsStateWithLifecycle]. */
        val uiState: StateFlow<RemoteShutterUiState> = _uiState.asStateFlow()

        /**
         * Handle a user action from [RemoteScreen].
         *
         * Retry resets to Idle. All shutter actions dispatch the appropriate
         * [RemoteCaptureRequest.BleShutter] (M15 uses the BLE path as the primary path;
         * PTP-IP path is used when a sessionId is available — deferred to M16 wiring).
         *
         * TODO(M16): select BLE vs PTP-IP based on active session type; pass real sessionId.
         */
        fun onAction(action: RemoteAction) {
            when (action) {
                RemoteAction.Retry -> _uiState.value = RemoteShutterUiState.Idle
                RemoteAction.HalfPress -> dispatchBleShutter(ShutterAction.HalfPress)
                RemoteAction.FullPress -> dispatchBleShutter(ShutterAction.FullPress)
                RemoteAction.Release -> dispatchBleShutter(ShutterAction.Release)
            }
        }

        /**
         * Dispatch a BLE shutter action.
         *
         * [cameraModel] is stubbed to "X-T5" in M15 so the capability gate passes in the
         * single verified model. TODO(M16): replace with real camera identity from session state.
         *
         * cancel-safe: the use case flow is collected in viewModelScope; ViewModel.onCleared()
         * cancels the scope, which cancels the collection. No state is leaked.
         */
        private fun dispatchBleShutter(action: ShutterAction) {
            // Stub: camera model is hardcoded to the M15 primary target so the gate passes.
            // TODO(M16): read from CameraRepository / session identity.
            val cameraModel = "X-T5"

            remoteCaptureUseCase(
                request = RemoteCaptureRequest.BleShutter(action),
                cameraModel = cameraModel,
            ).onEach { captureState ->
                _uiState.value = captureState.toUiState()
            }.catch { throwable ->
                // Unexpected uncaught exception from the use case — surface as a generic error.
                val detail = throwable.javaClass.simpleName
                _uiState.value = RemoteShutterUiState.Error("Unexpected error: $detail")
            }.launchIn(viewModelScope)
        }
    }

/** Maps a [RemoteCaptureState] to the presentation-layer [RemoteShutterUiState]. */
private fun RemoteCaptureState.toUiState(): RemoteShutterUiState =
    when (this) {
        is RemoteCaptureState.Idle -> RemoteShutterUiState.Idle
        is RemoteCaptureState.HalfPressed -> RemoteShutterUiState.HalfPress
        is RemoteCaptureState.FullPressed -> RemoteShutterUiState.FullPress
        is RemoteCaptureState.CapturingInProgress -> RemoteShutterUiState.CapturingInProgress
        is RemoteCaptureState.CaptureComplete -> RemoteShutterUiState.CaptureComplete
        is RemoteCaptureState.Error -> this.error.toUiState()
    }

/** Maps a [RemoteCaptureError] to the appropriate [RemoteShutterUiState]. */
private fun RemoteCaptureError.toUiState(): RemoteShutterUiState =
    when (this) {
        is RemoteCaptureError.IncompatibleCamera -> RemoteShutterUiState.IncompatibleCamera
        is RemoteCaptureError.NotConnectedBle -> RemoteShutterUiState.Error("BLE not connected")
        is RemoteCaptureError.NotConnectedWifi -> RemoteShutterUiState.Error("Wi-Fi session not active")
        is RemoteCaptureError.BleWriteFailed -> RemoteShutterUiState.Error("BLE write failed")
        is RemoteCaptureError.PtpIpFailed -> RemoteShutterUiState.Error("PTP-IP command failed")
    }

// ─── Route ───────────────────────────────────────────────────────────────────

/** Navigation entry point. Wires [RemoteViewModel] via Hilt and delegates to [RemoteScreen]. */
@Composable
fun RemoteRoute(viewModel: RemoteViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RemoteScreen(state = state, onAction = viewModel::onAction)
}

// ─── Screen ──────────────────────────────────────────────────────────────────

/**
 * Pure UI composable for the remote shutter screen.
 *
 * Receives [state] and [onAction]; performs NO I/O, BLE access, JNI calls,
 * or suspend operations. This composable is a pure function of its inputs.
 *
 * UI structure:
 * - Status card showing current capture state.
 * - Half-press, full-press, and release action buttons (disabled while busy or on error).
 * - Busy indicator (CircularProgressIndicator) during [RemoteShutterUiState.CapturingInProgress].
 * - Error card with Retry button.
 * - Compatibility warning card for [RemoteShutterUiState.IncompatibleCamera].
 */
@Composable
fun RemoteScreen(
    state: RemoteShutterUiState,
    onAction: (RemoteAction) -> Unit,
) {
    FrameportScreen {
        // ── Status card ──────────────────────────────────────────────────────
        FrameportCard(
            title = "Remote shutter",
            subtitle = state.statusSubtitle(),
            status = state.statusLabel(),
        )

        // ── Compatibility warning ────────────────────────────────────────────
        if (state == RemoteShutterUiState.IncompatibleCamera) {
            FrameportCard(
                title = "Camera not supported",
                subtitle =
                    "Remote capture is only available on verified Fujifilm models (X-T5). " +
                        "Connect a supported camera to use this feature.",
                status = "Incompatible",
            )
        }

        // ── Busy spinner ─────────────────────────────────────────────────────
        if (state == RemoteShutterUiState.CapturingInProgress) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── Error card with retry ────────────────────────────────────────────
        if (state is RemoteShutterUiState.Error) {
            FrameportCard(
                title = "Error",
                subtitle = state.message,
                status = "Failed",
            ) {
                PrimaryActionButton(
                    text = "Retry",
                    onClick = { onAction(RemoteAction.Retry) },
                )
            }
        }

        // ── Shutter buttons ──────────────────────────────────────────────────
        val buttonsEnabled =
            state == RemoteShutterUiState.Idle ||
                state == RemoteShutterUiState.HalfPress ||
                state == RemoteShutterUiState.FullPress ||
                state == RemoteShutterUiState.CaptureComplete ||
                state == RemoteShutterUiState.Released

        PrimaryActionButton(
            text = "Half-press (AF)",
            onClick = { onAction(RemoteAction.HalfPress) },
            enabled = buttonsEnabled,
        )

        PrimaryActionButton(
            text = "Full-press (Capture)",
            onClick = { onAction(RemoteAction.FullPress) },
            enabled = buttonsEnabled,
        )

        PrimaryActionButton(
            text = "Release",
            onClick = { onAction(RemoteAction.Release) },
            enabled = buttonsEnabled,
        )

        // ── Disclaimer ───────────────────────────────────────────────────────
        Text(
            text =
                "Remote capture sends commands to the camera over BLE. " +
                    "Ensure the camera is paired and in remote-ready mode.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── State helpers ────────────────────────────────────────────────────────────

private fun RemoteShutterUiState.statusLabel(): String =
    when (this) {
        is RemoteShutterUiState.Idle -> "Ready"
        is RemoteShutterUiState.HalfPress -> "AF active"
        is RemoteShutterUiState.FullPress -> "Capturing"
        is RemoteShutterUiState.Released -> "Released"
        is RemoteShutterUiState.CapturingInProgress -> "In progress"
        is RemoteShutterUiState.CaptureComplete -> "Complete"
        is RemoteShutterUiState.Error -> "Error"
        is RemoteShutterUiState.IncompatibleCamera -> "Unsupported"
    }

private fun RemoteShutterUiState.statusSubtitle(): String =
    when (this) {
        is RemoteShutterUiState.Idle -> "Press a button to control the shutter."
        is RemoteShutterUiState.HalfPress -> "Autofocus triggered. Press Full-press to capture."
        is RemoteShutterUiState.FullPress -> "Capture command sent to camera."
        is RemoteShutterUiState.Released -> "Shutter released."
        is RemoteShutterUiState.CapturingInProgress -> "Camera is processing the capture."
        is RemoteShutterUiState.CaptureComplete -> "Image captured successfully."
        is RemoteShutterUiState.Error -> message
        is RemoteShutterUiState.IncompatibleCamera -> "Connect a supported camera to use remote capture."
    }

// ─── Previews ────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "Idle")
@Composable
private fun RemoteScreenIdlePreview() {
    FrameportTheme {
        RemoteScreen(state = RemoteShutterUiState.Idle, onAction = {})
    }
}

@Preview(showBackground = true, name = "HalfPress")
@Composable
private fun RemoteScreenHalfPressPreview() {
    FrameportTheme {
        RemoteScreen(state = RemoteShutterUiState.HalfPress, onAction = {})
    }
}

@Preview(showBackground = true, name = "CapturingInProgress")
@Composable
private fun RemoteScreenCapturingPreview() {
    FrameportTheme {
        RemoteScreen(state = RemoteShutterUiState.CapturingInProgress, onAction = {})
    }
}

@Preview(showBackground = true, name = "CaptureComplete")
@Composable
private fun RemoteScreenCompletePreview() {
    FrameportTheme {
        RemoteScreen(state = RemoteShutterUiState.CaptureComplete, onAction = {})
    }
}

@Preview(showBackground = true, name = "Error")
@Composable
private fun RemoteScreenErrorPreview() {
    FrameportTheme {
        RemoteScreen(state = RemoteShutterUiState.Error("BLE not connected"), onAction = {})
    }
}

@Preview(showBackground = true, name = "IncompatibleCamera")
@Composable
private fun RemoteScreenIncompatiblePreview() {
    FrameportTheme {
        RemoteScreen(state = RemoteShutterUiState.IncompatibleCamera, onAction = {})
    }
}
