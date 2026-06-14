package dev.po4yka.frameport.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import dev.po4yka.frameport.feature.connection.CameraConnectRoute
import dev.po4yka.frameport.feature.connection.CameraScanRoute
import dev.po4yka.frameport.feature.diagnostics.DiagnosticsRoute
import dev.po4yka.frameport.feature.gallery.GalleryRoute
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
                        GalleryRoute()
                    }

                    FrameportDestination.Import -> {
                        ImportRoute()
                    }

                    FrameportDestination.Remote -> {
                        RemoteRoute()
                    }

                    FrameportDestination.LiveView -> {
                        LiveViewRoute()
                    }

                    FrameportDestination.Diagnostics -> {
                        DiagnosticsRoute()
                    }

                    FrameportDestination.Settings -> {
                        SettingsRoute()
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
