package dev.po4yka.frameport.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Shared semantic color tokens for Frameport UI.
 *
 * All values are derived from the Material 3 [MaterialTheme.colorScheme] so they
 * automatically adapt to light/dark mode without a custom color system.
 *
 * Rules:
 * - Never hardcode hex literals in feature screens; always use these tokens or
 *   raw [MaterialTheme.colorScheme] roles.
 * - Do not add tokens here unless at least two feature screens share the semantic.
 *
 * Token families:
 * 1. [FormatBadgeColors]  — container/content color pair for [CameraMediaFormat] badges.
 * 2. [SessionStateColors] — container/content color pair for [CameraSessionState] indicators.
 * 3. [ErrorSeverityColors] — container/content color pair for [FrameportError] severity chips.
 */
object FrameportTokens {
    /**
     * Container + content color pair.
     *
     * Use [container] as the chip/badge background; use [content] for text/icon on top.
     */
    data class ColorPair(
        val container: Color,
        val content: Color,
    )

    // ─── Format badge colors ──────────────────────────────────────────────────

    /**
     * Colors for camera media format badges (JPEG, RAF, HEIF, MOV, Unknown).
     *
     * Mapped to M3 color roles:
     * - JPEG  → Primary (most common format; visually prominent)
     * - RAF   → Tertiary (RAW; distinctive but secondary)
     * - HEIF  → Secondary (compressed; mid-importance)
     * - MOV   → Secondary variant (video; same family as HEIF)
     * - Unknown → Surface variant (de-emphasized)
     */
    object FormatBadgeColors {
        @Composable
        fun jpeg(): ColorPair =
            ColorPair(
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )

        @Composable
        fun raf(): ColorPair =
            ColorPair(
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )

        @Composable
        fun heif(): ColorPair =
            ColorPair(
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )

        @Composable
        fun mov(): ColorPair =
            ColorPair(
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )

        @Composable
        fun unknown(): ColorPair =
            ColorPair(
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }

    // ─── Session state colors ─────────────────────────────────────────────────

    /**
     * Colors for camera session state indicators (Idle, Connecting, Ready, Failed, Closed).
     *
     * Mapped to M3 color roles:
     * - Idle      → Surface variant (neutral/inactive)
     * - Connecting → Secondary container (in-progress)
     * - Ready     → Primary container (success/active)
     * - Failed    → Error container (failure)
     * - Closed    → Surface variant (ended/neutral)
     */
    object SessionStateColors {
        @Composable
        fun idle(): ColorPair =
            ColorPair(
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        @Composable
        fun connecting(): ColorPair =
            ColorPair(
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )

        @Composable
        fun ready(): ColorPair =
            ColorPair(
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )

        @Composable
        fun failed(): ColorPair =
            ColorPair(
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )

        @Composable
        fun closed(): ColorPair =
            ColorPair(
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }

    // ─── Error severity colors ────────────────────────────────────────────────

    /**
     * Colors for [FrameportError] severity chips displayed in connection/import screens.
     *
     * Severity mapping:
     * - Permission denied → Error container (user must act; blocking)
     * - Transport unavailable → Error container (blocking transport failure)
     * - Protocol unavailable → Error container (blocking protocol failure)
     * - Media unavailable → Tertiary container (non-blocking; item-level failure)
     * - Unknown → Surface variant (unclassified; de-emphasized)
     */
    object ErrorSeverityColors {
        @Composable
        fun blocking(): ColorPair =
            ColorPair(
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )

        @Composable
        fun itemLevel(): ColorPair =
            ColorPair(
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )

        @Composable
        fun unknown(): ColorPair =
            ColorPair(
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}
