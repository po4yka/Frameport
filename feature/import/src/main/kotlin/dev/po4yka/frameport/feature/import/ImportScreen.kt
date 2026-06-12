package dev.po4yka.frameport.feature.importmedia

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.CameraMediaRepository
import dev.po4yka.frameport.core.designsystem.EmptyState
import dev.po4yka.frameport.core.designsystem.FrameportCard
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class ImportUiState(
    val queuedCount: Int = 0,
    val message: String = "Import queue is empty.",
)

sealed interface ImportAction {
    data object Refresh : ImportAction
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val cameraMediaRepository: CameraMediaRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = mutableState.asStateFlow()

    fun onAction(action: ImportAction) {
        when (action) {
            ImportAction.Refresh -> mutableState.value = ImportUiState(message = "No fake import jobs are queued.")
        }
    }
}

@Composable
fun ImportRoute(viewModel: ImportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ImportScreen(state = state, onAction = viewModel::onAction)
}

@Composable
fun ImportScreen(
    state: ImportUiState,
    onAction: (ImportAction) -> Unit,
) {
    FrameportScreen {
        FrameportCard(
            title = "Import queue",
            subtitle = "${state.queuedCount} queued items",
            status = "No storage access yet",
        )
        EmptyState(
            title = "Nothing to import",
            message = state.message,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ImportScreenPreview() {
    FrameportTheme {
        ImportScreen(state = ImportUiState(), onAction = {})
    }
}
