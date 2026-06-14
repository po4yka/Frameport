package dev.po4yka.frameport.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.MediaRepository
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.domain.ListMediaUseCase
import dev.po4yka.frameport.core.model.FrameportError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Gallery screen.
 *
 * Responsibilities:
 * - Load media objects for a given session via [ListMediaUseCase].
 * - Maintain selection state ([toggleSelection], [selectAll], [clearSelection]).
 * - Map [FrameportError] -> [GalleryError] so the UI only deals with the UI discriminant.
 *
 * Boundary rules:
 * - MUST NOT import :camera:wifi, :camera:bluetooth, :camera:usb, or :camera:media.
 * - MUST NOT read Room or DataStore directly.
 * - All I/O runs on [viewModelScope]; never GlobalScope.
 * - [MediaRepository] is injected for thumbnail fetching (future use); thumbnails are
 *   fetched lazily per-item when the composable requests them, NOT eagerly here.
 */
@HiltViewModel
class GalleryViewModel
    @Inject
    constructor(
        private val listMediaUseCase: ListMediaUseCase,
        // Injected for thumbnail access; reserved for future per-item thumbnail loading.
        @Suppress("UnusedPrivateProperty")
        private val mediaRepository: MediaRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<GalleryUiState>(GalleryUiState.Loading)

        /** Observed by the Composable via [collectAsStateWithLifecycle]. */
        val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

        /**
         * Loads media for the given [sessionId].
         *
         * Transitions: Loading -> Loaded | Empty | Error.
         *
         * Idempotent — safe to call on retry or pull-to-refresh.
         */
        fun load(sessionId: SessionId) {
            _uiState.value = GalleryUiState.Loading
            viewModelScope.launch {
                val result = listMediaUseCase(sessionId)
                _uiState.value =
                    result.fold(
                        onSuccess = { objects ->
                            if (objects.isEmpty()) {
                                GalleryUiState.Empty
                            } else {
                                GalleryUiState.Loaded(
                                    items = objects.map { it.toGalleryItem(isSelected = false) },
                                    selectedHandles = emptySet(),
                                )
                            }
                        },
                        onFailure = { throwable ->
                            GalleryUiState.Error(throwable.toGalleryError())
                        },
                    )
            }
        }

        /**
         * Toggles selection for [handle].
         *
         * No-op when [uiState] is not [GalleryUiState.Loaded].
         */
        fun toggleSelection(handle: CameraObjectHandle) {
            val current = _uiState.value as? GalleryUiState.Loaded ?: return
            val newSelected =
                if (handle in current.selectedHandles) {
                    current.selectedHandles - handle
                } else {
                    current.selectedHandles + handle
                }
            _uiState.value =
                current.copy(
                    items =
                        current.items.map { item ->
                            item.copy(isSelected = item.handle in newSelected)
                        },
                    selectedHandles = newSelected,
                )
        }

        /**
         * Selects all items in the current [GalleryUiState.Loaded] state.
         *
         * No-op when state is not [GalleryUiState.Loaded].
         */
        fun selectAll() {
            val current = _uiState.value as? GalleryUiState.Loaded ?: return
            val allHandles = current.items.map { it.handle }.toSet()
            _uiState.value =
                current.copy(
                    items = current.items.map { it.copy(isSelected = true) },
                    selectedHandles = allHandles,
                )
        }

        /**
         * Deselects all items in the current [GalleryUiState.Loaded] state.
         *
         * No-op when state is not [GalleryUiState.Loaded].
         */
        fun clearSelection() {
            val current = _uiState.value as? GalleryUiState.Loaded ?: return
            _uiState.value =
                current.copy(
                    items = current.items.map { it.copy(isSelected = false) },
                    selectedHandles = emptySet(),
                )
        }

        // ─── Error mapping ────────────────────────────────────────────────────

        private fun Throwable.toGalleryError(): GalleryError {
            val framportError =
                (this as? dev.po4yka.frameport.core.model.FrameportErrorException)?.error
                    ?: return GalleryError.Unknown(message ?: "listMedia failed")
            return framportError.toGalleryError()
        }

        private fun FrameportError.toGalleryError(): GalleryError =
            when (this) {
                is FrameportError.PermissionDenied -> GalleryError.NoSession
                is FrameportError.TransportUnavailable -> GalleryError.TransportUnavailable
                is FrameportError.ProtocolUnavailable -> GalleryError.ProtocolFailure
                is FrameportError.MediaUnavailable -> GalleryError.MediaUnavailable(message)
                is FrameportError.Unknown -> GalleryError.Unknown(message)
            }
    }
