package dev.po4yka.frameport.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.core.storage.timeline.LocalTimelineStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for the Local Timeline screen.
 *
 * Boundary rules:
 * - Injects [LocalTimelineStore] (interface) only — never Room DAOs, DataStore, or
 *   platform APIs directly. The DAO is hidden behind the store abstraction in :core:storage.
 * - All state is derived via Flow operators; no coroutine is launched manually.
 * - Exposes [uiState] as a [StateFlow] initialised to [LocalTimelineUiState.Loading] so the
 *   Composable always has a valid non-null initial state.
 * - No outbound network, no analytics, no camera-session interaction.
 */
@HiltViewModel
class LocalTimelineViewModel
    @Inject
    constructor(
        // cancel-safe: LocalTimelineStore.observeSessions() is a Room-backed Flow;
        // Room unsubscribes the InvalidationTracker observer cleanly on cancellation.
        localTimelineStore: LocalTimelineStore,
    ) : ViewModel() {
        val uiState: StateFlow<LocalTimelineUiState> =
            localTimelineStore
                .observeSessions()
                .map { sessions ->
                    if (sessions.isEmpty()) {
                        LocalTimelineUiState.Empty
                    } else {
                        LocalTimelineUiState.Loaded(sessions)
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                    initialValue = LocalTimelineUiState.Loading,
                )
    }
