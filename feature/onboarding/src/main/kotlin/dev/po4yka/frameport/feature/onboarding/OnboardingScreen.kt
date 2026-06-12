package dev.po4yka.frameport.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.po4yka.frameport.core.designsystem.FrameportCard
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import dev.po4yka.frameport.core.designsystem.PrimaryActionButton

data class OnboardingUiState(
    val title: String = "Frameport",
    val subtitle: String = "Local-first Fujifilm companion",
)

sealed interface OnboardingAction {
    data object Continue : OnboardingAction
}

@Composable
fun OnboardingRoute(onContinue: () -> Unit) {
    OnboardingScreen(
        state = OnboardingUiState(),
        onAction = { action ->
            when (action) {
                OnboardingAction.Continue -> onContinue()
            }
        },
    )
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onAction: (OnboardingAction) -> Unit,
) {
    FrameportScreen {
        FrameportCard(
            title = state.title,
            subtitle = state.subtitle,
            status = "Android-only MVP",
        )
        FrameportCard(
            title = "Private by default",
            subtitle = "No account, cloud sync, analytics, firmware updates, or real camera communication are active in this scaffold.",
        )
        PrimaryActionButton(
            text = "Continue",
            onClick = { onAction(OnboardingAction.Continue) },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenPreview() {
    FrameportTheme {
        OnboardingScreen(state = OnboardingUiState(), onAction = {})
    }
}
