package dev.po4yka.frameport.feature.gallery

import androidx.compose.runtime.Immutable
import dev.po4yka.frameport.core.model.ImportSession

/**
 * UI state for the Local Timeline screen.
 *
 * The Local Timeline lives in :feature:gallery because gallery is the media-viewing feature
 * and already carries the Coil dependency required for thumbnail rendering. Collocating the
 * timeline avoids a new module while preserving the feature boundary: timeline shows
 * *imported* media (local), while the gallery shows *camera* media (remote session).
 */
sealed interface LocalTimelineUiState {
    /** Initial state while the Room Flow is being collected for the first time. */
    data object Loading : LocalTimelineUiState

    /** The timeline has been loaded but no import sessions exist yet. */
    data object Empty : LocalTimelineUiState

    /**
     * At least one import session is available.
     *
     * [sessions] is ordered by [ImportSession.endedAtEpochMs] descending (most recent first),
     * as guaranteed by [LocalTimelineStore.observeSessions].
     */
    data class Loaded(
        val sessions: List<ImportSession>,
    ) : LocalTimelineUiState {
        val isEmpty: Boolean get() = sessions.isEmpty()
    }
}

/**
 * Stable wrapper for a list of thumbnail URI strings to help the Compose compiler
 * infer skippability on [SessionCard]. Without this wrapper, [List<String>] is
 * treated as unstable because List is a mutable-capable interface.
 *
 * Residual instability note: [ImportSession] itself is a plain data class whose
 * [List<String>] thumbnailUris field will be seen as unstable by the Compose compiler
 * unless strong-skipping mode is enabled. Wrapping at the card boundary here limits
 * the recomposition blast radius to the thumbnail grid only.
 */
@Immutable
data class StableThumbnailUris(
    val uris: List<String>,
)
