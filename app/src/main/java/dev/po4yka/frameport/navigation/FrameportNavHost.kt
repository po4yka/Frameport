package dev.po4yka.frameport.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.feature.connection.CameraConnectRoute
import dev.po4yka.frameport.feature.connection.CameraScanRoute
import dev.po4yka.frameport.feature.diagnostics.DiagnosticsRoute
import dev.po4yka.frameport.feature.gallery.GalleryRoute
import dev.po4yka.frameport.feature.gallery.LocalTimelineRoute
import dev.po4yka.frameport.feature.importmedia.ImportRoute
import dev.po4yka.frameport.feature.liveview.LiveViewRoute
import dev.po4yka.frameport.feature.onboarding.OnboardingRoute
import dev.po4yka.frameport.feature.remote.RemoteRoute
import dev.po4yka.frameport.feature.settings.SettingsRoute
import dev.po4yka.frameport.ui.HomeRoute

@Composable
fun FrameportNavHost(
    backStack: MutableList<NavKey>,
    modifier: Modifier = Modifier,
) {
    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        },
        entryProvider = { key ->
            NavEntry(key) { destination ->
                when (destination) {
                    FrameportDestination.Onboarding -> {
                        OnboardingRoute(onContinue = { backStack.replaceWith(FrameportDestination.Home) })
                    }

                    FrameportDestination.Home -> {
                        HomeRoute(onNavigate = backStack::navigate)
                    }

                    FrameportDestination.CameraScan -> {
                        CameraScanRoute(
                            onConnectManually = { backStack.navigate(FrameportDestination.CameraConnect) },
                            onCancel = { backStack.popToHome() },
                        )
                    }

                    FrameportDestination.CameraConnect -> {
                        CameraConnectRoute(onCancel = { backStack.popToHome() })
                    }

                    FrameportDestination.Gallery -> {
                        // SessionId(0L) is a scaffold sentinel — no real session in v1 scaffold.
                        // The real session ID will be passed once camera connection flows through.
                        GalleryRoute(
                            sessionId = SessionId(0L),
                            onImportSelected = { backStack.navigate(FrameportDestination.Import) },
                        )
                    }

                    FrameportDestination.Import -> {
                        ImportRoute()
                    }

                    FrameportDestination.Remote -> {
                        RemoteRoute()
                    }

                    FrameportDestination.LiveView -> {
                        // TODO(frameport): extract sessionId and liveViewFd from nav args when
                        // the connection → liveview handoff is wired in M16.
                        // Stub values: sessionId=-1 (no-op session), liveViewFd=-1 (no socket).
                        LiveViewRoute(sessionId = -1L, liveViewFd = -1)
                    }

                    FrameportDestination.Diagnostics -> {
                        DiagnosticsRoute()
                    }

                    FrameportDestination.Settings -> {
                        SettingsRoute()
                    }

                    FrameportDestination.LocalTimeline -> {
                        LocalTimelineRoute(onBack = { backStack.removeAt(backStack.lastIndex) })
                    }

                    else -> {
                        HomeRoute(onNavigate = backStack::navigate)
                    }
                }
            }
        },
    )
}

private fun MutableList<NavKey>.navigate(destination: FrameportDestination) {
    add(destination)
}

private fun MutableList<NavKey>.replaceWith(destination: FrameportDestination) {
    clear()
    add(destination)
}

private fun MutableList<NavKey>.popToHome() {
    clear()
    add(FrameportDestination.Home)
}
