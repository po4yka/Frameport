package dev.po4yka.frameport.feature.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.CameraConnectionManager
import dev.po4yka.frameport.core.designsystem.FrameportCard
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class RemoteUiState(
    val title: String = "Remote shutter",
    val message: String = "Remote capture controls are planned and intentionally inactive.",
)

sealed interface RemoteAction {
    data object Refresh : RemoteAction
}

@HiltViewModel
class RemoteViewModel @Inject constructor(
    private val cameraConnectionManager: CameraConnectionManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = mutableState.asStateFlow()

    fun onAction(action: RemoteAction) {
        when (action) {
            RemoteAction.Refresh -> mutableState.value = RemoteUiState()
        }
    }
}

@Composable
fun RemoteRoute(viewModel: RemoteViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RemoteScreen(state = state, onAction = viewModel::onAction)
}

@Composable
fun RemoteScreen(
    state: RemoteUiState,
    onAction: (RemoteAction) -> Unit,
) {
    FrameportScreen {
        FrameportCard(
            title = state.title,
            subtitle = state.message,
            status = "Planned",
        )
        FrameportCard(
            title = "No capture behavior",
            subtitle = "This screen does not trigger camera commands, JNI calls, or network traffic.",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoteScreenPreview() {
    FrameportTheme {
        RemoteScreen(state = RemoteUiState(), onAction = {})
    }
}
