package dev.po4yka.frameport.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.core.designsystem.FrameportCard
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class SettingsUiState(
    val privacyMode: String = "Local-only",
)

sealed interface SettingsAction {
    data object Refresh : SettingsAction
}

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    fun onAction(action: SettingsAction) {
        when (action) {
            SettingsAction.Refresh -> mutableState.value = SettingsUiState()
        }
    }
}

@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(state = state, onAction = viewModel::onAction)
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
) {
    FrameportScreen {
        FrameportCard(
            title = "Settings",
            subtitle = "Initial local settings shell.",
            status = state.privacyMode,
        )
        FrameportCard(
            title = "No account",
            subtitle = "Cloud sync, analytics, telemetry, and firmware update settings are intentionally absent.",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    FrameportTheme {
        SettingsScreen(state = SettingsUiState(), onAction = {})
    }
}
