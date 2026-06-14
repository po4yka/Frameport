package dev.po4yka.frameport.feature.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.MediaRepository
import dev.po4yka.frameport.core.designsystem.EmptyState
import dev.po4yka.frameport.core.designsystem.FrameportCard
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class GalleryUiState(
    val itemCount: Int = 0,
    val message: String = "Connect a camera before browsing media.",
)

sealed interface GalleryAction {
    data object Refresh : GalleryAction
}

@HiltViewModel
class GalleryViewModel
    @Inject
    constructor(
        private val mediaRepository: MediaRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(GalleryUiState())
        val uiState: StateFlow<GalleryUiState> = mutableState.asStateFlow()

        fun onAction(action: GalleryAction) {
            when (action) {
                GalleryAction.Refresh -> {
                    mutableState.value =
                        GalleryUiState(
                            itemCount = 0,
                            message = "No fake camera media is available yet.",
                        )
                }
            }
        }
    }

@Composable
fun GalleryRoute(viewModel: GalleryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GalleryScreen(state = state, onAction = viewModel::onAction)
}

@Composable
fun GalleryScreen(
    state: GalleryUiState,
    onAction: (GalleryAction) -> Unit,
) {
    FrameportScreen {
        FrameportCard(
            title = "Gallery",
            subtitle = "${state.itemCount} media objects",
            status = "Fake repository",
        )
        EmptyState(
            title = "No camera media",
            message = state.message,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun GalleryScreenPreview() {
    FrameportTheme {
        GalleryScreen(state = GalleryUiState(), onAction = {})
    }
}
