package dev.po4yka.frameport.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed interface FrameportDestination : NavKey {
    val title: String

    @Serializable
    data object Onboarding : FrameportDestination {
        override val title: String = "Onboarding"
    }

    @Serializable
    data object Home : FrameportDestination {
        override val title: String = "Frameport"
    }

    @Serializable
    data object CameraScan : FrameportDestination {
        override val title: String = "Camera scan"
    }

    @Serializable
    data object CameraConnect : FrameportDestination {
        override val title: String = "Camera connection"
    }

    @Serializable
    data object Gallery : FrameportDestination {
        override val title: String = "Gallery"
    }

    @Serializable
    data object Import : FrameportDestination {
        override val title: String = "Import queue"
    }

    @Serializable
    data object Remote : FrameportDestination {
        override val title: String = "Remote"
    }

    @Serializable
    data object LiveView : FrameportDestination {
        override val title: String = "Live view"
    }

    @Serializable
    data object Diagnostics : FrameportDestination {
        override val title: String = "Diagnostics"
    }

    @Serializable
    data object Settings : FrameportDestination {
        override val title: String = "Settings"
    }

    @Serializable
    data object LocalTimeline : FrameportDestination {
        override val title: String = "Timeline"
    }
}
