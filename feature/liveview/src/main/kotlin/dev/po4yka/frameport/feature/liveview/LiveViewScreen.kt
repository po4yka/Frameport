package dev.po4yka.frameport.feature.liveview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.core.designsystem.EmptyState
import dev.po4yka.frameport.core.designsystem.FrameportCard
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class LiveViewUiState(
    val title: String = "Live view",
    val message: String = "Live view is planned for a later phase.",
)

sealed interface LiveViewAction {
    data object Refresh : LiveViewAction
}

@HiltViewModel
class LiveViewViewModel
    @Inject
    constructor(
        private val cameraRepository: CameraRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(LiveViewUiState())
        val uiState: StateFlow<LiveViewUiState> = mutableState.asStateFlow()

        fun onAction(action: LiveViewAction) {
            when (action) {
                LiveViewAction.Refresh -> mutableState.value = LiveViewUiState()
            }
        }
    }

@Composable
fun LiveViewRoute(viewModel: LiveViewViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LiveViewScreen(state = state, onAction = viewModel::onAction)
}

@Composable
fun LiveViewScreen(
    state: LiveViewUiState,
    onAction: (LiveViewAction) -> Unit,
) {
    FrameportScreen {
        FrameportCard(
            title = state.title,
            subtitle = "Future Rust code will own frame parsing and protocol state.",
            status = "Planned",
        )
        EmptyState(
            title = "No live feed",
            message = state.message,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LiveViewScreenPreview() {
    FrameportTheme {
        LiveViewScreen(state = LiveViewUiState(), onAction = {})
    }
}
