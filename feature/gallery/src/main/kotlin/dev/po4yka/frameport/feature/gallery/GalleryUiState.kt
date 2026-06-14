package dev.po4yka.frameport.feature.gallery

import dev.po4yka.frameport.camera.api.CameraMediaFormat
import dev.po4yka.frameport.camera.api.CameraMediaObject
import dev.po4yka.frameport.camera.api.CameraObjectHandle

/**
 * A single item in the gallery list, wrapping the domain object with derived UI fields.
 *
 * [label] is a generated display name: "FRP_<handle>.<ext>".
 * There is no raw filename in [CameraMediaObject]; we must never invent one.
 */
data class GalleryItem(
    val mediaObject: CameraMediaObject,
    val label: String,
    val isSelected: Boolean,
) {
    val handle: CameraObjectHandle get() = mediaObject.handle
    val format: CameraMediaFormat get() = mediaObject.format
    val sizeBytes: Long? get() = mediaObject.sizeBytes
}

/** Pure function — no side effects, easily unit-tested. */
fun CameraMediaObject.toGalleryItem(isSelected: Boolean = false): GalleryItem {
    val ext =
        when (format) {
            CameraMediaFormat.Jpeg -> "jpg"
            CameraMediaFormat.Raf -> "raf"
            CameraMediaFormat.Heif -> "heif"
            CameraMediaFormat.Mov -> "mov"
            CameraMediaFormat.Unknown -> "bin"
        }
    return GalleryItem(
        mediaObject = this,
        label = "FRP_${handle.value}.$ext",
        isSelected = isSelected,
    )
}

/** Format badge label shown in the UI chip. */
fun CameraMediaFormat.badgeLabel(): String =
    when (this) {
        CameraMediaFormat.Jpeg -> "JPEG"
        CameraMediaFormat.Raf -> "RAF"
        CameraMediaFormat.Heif -> "HEIF"
        CameraMediaFormat.Mov -> "MOV"
        CameraMediaFormat.Unknown -> "UNKNOWN"
    }

/**
 * Sealed UI state for the gallery screen.
 *
 * Initial state is [Loading]; never expose an uninitialized StateFlow.
 */
sealed interface GalleryUiState {
    /** Initial state; a media list load is in progress. */
    data object Loading : GalleryUiState

    /** Load completed and no media objects were returned. */
    data object Empty : GalleryUiState

    /**
     * Load completed with at least one item.
     *
     * [selectedHandles] drives the "Import Selected" FAB enabled state.
     */
    data class Loaded(
        val items: List<GalleryItem>,
        val selectedHandles: Set<CameraObjectHandle> = emptySet(),
    ) : GalleryUiState {
        val selectionCount: Int get() = selectedHandles.size
        val allSelected: Boolean get() = items.isNotEmpty() && selectedHandles.size == items.size
    }

    /**
     * Load failed with a typed [GalleryError].
     *
     * The Composable maps each variant to a human-readable string; raw messages are
     * never shown directly to the user.
     */
    data class Error(
        val error: GalleryError,
    ) : GalleryUiState
}

/**
 * Feature-local error discriminant.
 *
 * Mapped from [dev.po4yka.frameport.core.model.FrameportError] in the ViewModel.
 * Kept in this file so [GalleryUiState.Error] + [GalleryError] travel together.
 */
sealed interface GalleryError {
    /** Camera session is not active; the user must connect first. */
    data object NoSession : GalleryError

    /** The transport layer is unavailable (Wi-Fi not connected, etc.). */
    data object TransportUnavailable : GalleryError

    /** The camera protocol handshake failed or was rejected. */
    data object ProtocolFailure : GalleryError

    /** Media enumeration succeeded but the specific media object was unavailable. */
    data class MediaUnavailable(
        val detail: String,
    ) : GalleryError

    /** An unexpected error occurred. [detail] is a sanitized summary, not a raw stack trace. */
    data class Unknown(
        val detail: String,
    ) : GalleryError
}
