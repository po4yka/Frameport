package dev.po4yka.frameport.feature.importmedia

import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.TransferId
import dev.po4yka.frameport.core.model.FrameportError

/**
 * Progress snapshot for a single item currently transferring.
 *
 * [label] is a generated display name — never a raw camera filename.
 * [fraction] is clamped to [0f, 1f]; 0f when [totalBytes] is unknown (0).
 */
data class ImportItemProgress(
    val objectHandle: CameraObjectHandle,
    val transferId: TransferId,
    val label: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val fraction: Float,
)

/**
 * A single item waiting in the queue before transfer starts.
 */
data class ImportItem(
    val objectHandle: CameraObjectHandle,
    val label: String,
)

/** Terminal result recorded once an item leaves the active import set. */
sealed interface ImportResult {
    val label: String

    data class Imported(
        override val label: String,
        val localUri: String,
    ) : ImportResult

    data class Failed(
        override val label: String,
        val error: FrameportError,
    ) : ImportResult

    data class Cancelled(
        override val label: String,
    ) : ImportResult
}

/**
 * Top-level UI state for the import screen.
 *
 * Transitions: Idle -> Queued -> Importing -> Done.
 * Cancel All resets back to Idle from Queued.
 * Cancelling individual items during Importing removes them; when all items
 * are terminal the state transitions to Done.
 */
sealed interface ImportUiState {
    data object Idle : ImportUiState

    data class Queued(
        val queue: List<ImportItem>,
    ) : ImportUiState

    data class Importing(
        val items: List<ImportItemProgress>,
    ) : ImportUiState

    data class Done(
        val results: List<ImportResult>,
    ) : ImportUiState
}
