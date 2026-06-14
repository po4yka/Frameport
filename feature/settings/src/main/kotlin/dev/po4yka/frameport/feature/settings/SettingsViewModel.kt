package dev.po4yka.frameport.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the settings screen.
 *
 * Boundary rules:
 * - Injects [SettingsRepository] (interface) only — never DataStore, Room, or platform APIs directly.
 * - All writes run on [viewModelScope] (never GlobalScope).
 * - Exposes [uiState] as a [StateFlow] initialized to [SettingsUiState] (isLoading = true)
 *   so the screen always has a valid initial state.
 * - No outbound network, no analytics, no account logic.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val repository: SettingsRepository,
    ) : ViewModel() {
        val uiState: StateFlow<SettingsUiState> =
            repository.preferences
                .map { prefs -> SettingsUiState(isLoading = false, preferences = prefs) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                    initialValue = SettingsUiState(isLoading = true),
                )

        fun onAction(action: SettingsAction) {
            when (action) {
                is SettingsAction.SetAutoImport -> {
                    viewModelScope.launch {
                        repository.setAutoImportOnConnect(action.enabled)
                    }
                }

                is SettingsAction.ToggleFormatFilter -> {
                    viewModelScope.launch {
                        // Read the latest persisted value (not the WhileSubscribed StateFlow buffer,
                        // which can be stale under rapid toggles) so concurrent taps don't drop one.
                        val current =
                            repository.preferences
                                .first()
                                .formatFilter
                                .toMutableSet()
                        if (!current.add(action.format)) {
                            current.remove(action.format)
                        }
                        repository.setFormatFilter(current)
                    }
                }

                is SettingsAction.SetImportPathTemplate -> {
                    viewModelScope.launch {
                        repository.setImportPathTemplate(action.template)
                    }
                }

                SettingsAction.Refresh -> {
                    // DataStore flow automatically re-emits on every write; nothing to do.
                }
            }
        }
    }
