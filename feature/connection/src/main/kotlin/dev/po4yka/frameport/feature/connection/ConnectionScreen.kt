package dev.po4yka.frameport.feature.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.core.designsystem.FrameportCard
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import dev.po4yka.frameport.core.designsystem.PrimaryActionButton
import dev.po4yka.frameport.core.designsystem.StatusPill
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionUiState(
    val step: ConnectionStep = ConnectionStep.Idle,
    val detail: String = "No camera scan is running.",
    val canCancel: Boolean = false,
)

enum class ConnectionStep(
    val label: String,
) {
    Idle("Idle"),
    Scanning("Scanning"),
    CameraFound("Camera found"),
    RequestingWifi("Requesting Wi-Fi"),
    OpeningSession("Opening session"),
    Connected("Connected"),
    Error("Error"),
}

sealed interface ConnectionAction {
    data object StartScan : ConnectionAction

    data object ConnectManually : ConnectionAction

    data object Cancel : ConnectionAction
}

sealed interface ConnectionUiEvent {
    data object RequestManualConnection : ConnectionUiEvent

    data object Cancelled : ConnectionUiEvent
}

@HiltViewModel
class ConnectionViewModel
    @Inject
    constructor(
        private val cameraRepository: CameraRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(ConnectionUiState())
        val uiState: StateFlow<ConnectionUiState> = mutableState.asStateFlow()

        private val mutableEvents = MutableSharedFlow<ConnectionUiEvent>()
        val events: SharedFlow<ConnectionUiEvent> = mutableEvents.asSharedFlow()

        fun onAction(action: ConnectionAction) {
            when (action) {
                ConnectionAction.StartScan -> {
                    startPlaceholderScan()
                }

                ConnectionAction.ConnectManually -> {
                    viewModelScope.launch {
                        mutableEvents.emit(ConnectionUiEvent.RequestManualConnection)
                    }
                }

                ConnectionAction.Cancel -> {
                    viewModelScope.launch {
                        val currentState = cameraRepository.sessionState.value
                        if (currentState is CameraSessionState.SessionReady) {
                            cameraRepository.closeSession(currentState.sessionId)
                        }
                        mutableState.value = ConnectionUiState()
                        mutableEvents.emit(ConnectionUiEvent.Cancelled)
                    }
                }
            }
        }

        private fun startPlaceholderScan() {
            viewModelScope.launch {
                mutableState.value =
                    ConnectionUiState(
                        step = ConnectionStep.Scanning,
                        detail = "Looking for nearby cameras with a fake local state machine.",
                        canCancel = true,
                    )
                delay(200)
                mutableState.value =
                    ConnectionUiState(
                        step = ConnectionStep.CameraFound,
                        detail = "Placeholder X-T5 profile found. Real scan support is not implemented yet.",
                        canCancel = true,
                    )
            }
        }
    }

@Composable
fun CameraScanRoute(
    onConnectManually: () -> Unit,
    onCancel: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ConnectionUiEvent.RequestManualConnection -> onConnectManually()
                ConnectionUiEvent.Cancelled -> onCancel()
            }
        }
    }
    ConnectionScreen(state = state, onAction = viewModel::onAction)
}

@Composable
fun CameraConnectRoute(
    onCancel: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is ConnectionUiEvent.Cancelled) {
                onCancel()
            }
        }
    }
    ConnectionScreen(
        state =
            state.copy(
                step = ConnectionStep.RequestingWifi,
                detail = "Manual connection placeholder. Real Wi-Fi routing and native sessions are not implemented yet.",
                canCancel = true,
            ),
        onAction = viewModel::onAction,
    )
}

@Composable
fun ConnectionScreen(
    state: ConnectionUiState,
    onAction: (ConnectionAction) -> Unit,
) {
    FrameportScreen {
        FrameportCard(
            title = "Camera connection",
            subtitle = state.detail,
        ) {
            StatusPill(label = state.step.label)
        }
        FrameportCard(
            title = "Placeholder states",
            subtitle = "Idle, Scanning, Camera found, Requesting Wi-Fi, Opening session, Connected, and Error are UI states only.",
            status = "No real camera support",
        )
        PrimaryActionButton(text = "Start scan", onClick = { onAction(ConnectionAction.StartScan) })
        PrimaryActionButton(text = "Connect manually", onClick = { onAction(ConnectionAction.ConnectManually) })
        PrimaryActionButton(text = "Cancel", onClick = { onAction(ConnectionAction.Cancel) }, enabled = state.canCancel)
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionScreenPreview() {
    FrameportTheme {
        ConnectionScreen(
            state =
                ConnectionUiState(
                    step = ConnectionStep.CameraFound,
                    detail = "Placeholder X-T5 profile found.",
                    canCancel = true,
                ),
            onAction = {},
        )
    }
}
