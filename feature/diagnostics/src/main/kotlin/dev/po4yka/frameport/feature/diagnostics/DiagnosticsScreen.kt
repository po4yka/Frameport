package dev.po4yka.frameport.feature.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.DiagnosticsRepository
import dev.po4yka.frameport.core.designsystem.FrameportCard
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import dev.po4yka.frameport.core.designsystem.StatusPill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class DiagnosticsUiState(
    val categories: List<String> =
        listOf(
            "Permissions",
            "Bluetooth",
            "Wi-Fi",
            "Native SDK",
            "Camera protocol",
            "Storage",
        ),
    val privacyNote: String = "Diagnostics are local-only. Sensitive values must be redacted before any future export.",
)

sealed interface DiagnosticsAction {
    data object Refresh : DiagnosticsAction
}

@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        private val diagnosticsRepository: DiagnosticsRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(DiagnosticsUiState())
        val uiState: StateFlow<DiagnosticsUiState> = mutableState.asStateFlow()

        fun onAction(action: DiagnosticsAction) {
            when (action) {
                DiagnosticsAction.Refresh -> mutableState.value = DiagnosticsUiState()
            }
        }
    }

@Composable
fun DiagnosticsRoute(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DiagnosticsScreen(state = state, onAction = viewModel::onAction)
}

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onAction: (DiagnosticsAction) -> Unit,
) {
    FrameportScreen {
        FrameportCard(
            title = "Diagnostics",
            subtitle = state.privacyNote,
            status = "Local-only",
        )
        state.categories.forEach { category ->
            FrameportCard(title = category, subtitle = "No diagnostic checks are implemented yet.") {
                StatusPill(label = "Not implemented yet")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DiagnosticsScreenPreview() {
    FrameportTheme {
        DiagnosticsScreen(state = DiagnosticsUiState(), onAction = {})
    }
}
