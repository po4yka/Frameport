package dev.po4yka.frameport.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.po4yka.frameport.core.designsystem.FrameportCard
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import dev.po4yka.frameport.core.designsystem.PrimaryActionButton
import dev.po4yka.frameport.navigation.FrameportDestination

@Composable
fun HomeRoute(onNavigate: (FrameportDestination) -> Unit) {
    HomeScreen(
        onConnectCamera = { onNavigate(FrameportDestination.CameraScan) },
        onBrowseMedia = { onNavigate(FrameportDestination.Gallery) },
        onTimeline = { onNavigate(FrameportDestination.LocalTimeline) },
        onImportQueue = { onNavigate(FrameportDestination.Import) },
        onRemote = { onNavigate(FrameportDestination.Remote) },
        onLiveView = { onNavigate(FrameportDestination.LiveView) },
        onDiagnostics = { onNavigate(FrameportDestination.Diagnostics) },
        onSettings = { onNavigate(FrameportDestination.Settings) },
    )
}

@Composable
fun HomeScreen(
    onConnectCamera: () -> Unit,
    onBrowseMedia: () -> Unit,
    onTimeline: () -> Unit,
    onImportQueue: () -> Unit,
    onRemote: () -> Unit,
    onLiveView: () -> Unit,
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit,
) {
    FrameportScreen {
        FrameportCard(
            title = "Frameport",
            subtitle = "Local-first Fujifilm companion",
        )
        HomeActionCard("Connect camera", "Manual connection flow scaffold.", "Not implemented yet", onConnectCamera)
        HomeActionCard("Browse camera media", "Empty gallery backed by fake repositories.", "Planned", onBrowseMedia)
        HomeActionCard("Import timeline", "Local-only timeline of past import sessions.", "M18", onTimeline)
        HomeActionCard("Import queue", "Local import queue placeholder.", "Planned", onImportQueue)
        HomeActionCard("Remote shutter", "Remote capture is outside the current MVP.", "Planned", onRemote)
        HomeActionCard("Live view", "Live view parsing will be Rust-owned later.", "Planned", onLiveView)
        HomeActionCard("Diagnostics", "Local-only diagnostics categories.", "Not implemented yet", onDiagnostics)
        HomeActionCard("Settings", "Local settings placeholder.", "Planned", onSettings)
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    subtitle: String,
    status: String,
    onClick: () -> Unit,
) {
    FrameportCard(
        title = title,
        subtitle = subtitle,
        status = status,
    ) {
        PrimaryActionButton(text = title, onClick = onClick)
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    FrameportTheme {
        HomeScreen(
            onConnectCamera = {},
            onBrowseMedia = {},
            onTimeline = {},
            onImportQueue = {},
            onRemote = {},
            onLiveView = {},
            onDiagnostics = {},
            onSettings = {},
        )
    }
}
