package dev.po4yka.frameport.feature.importmedia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.ImportState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.TransferId
import dev.po4yka.frameport.camera.domain.CancelImportUseCase
import dev.po4yka.frameport.camera.domain.ImportObjectUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manages the import queue for a camera session.
 *
 * State machine:
 *   Idle -> Queued (enqueueItems) -> Importing (startImport) -> Done
 *   Queued -> Idle (cancelAll)
 *   Importing: per-item cancel via [cancelItem]; when all items are terminal -> Done.
 *
 * All mutations run on [viewModelScope]. No GlobalScope, no LiveData.
 */
@HiltViewModel
class ImportViewModel
    @Inject
    constructor(
        private val importObjectUseCase: ImportObjectUseCase,
        private val cancelImportUseCase: CancelImportUseCase,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
        val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

        // Tracks transferId -> CameraObjectHandle for active imports so cancelItem can look up the handle.
        private val activeTransferIds = mutableMapOf<TransferId, CameraObjectHandle>()

        // Accumulates each item's terminal result so the final Done state carries every item's
        // outcome, not just the last-finishing one. Mutated only from viewModelScope collections,
        // which run serially on the Main dispatcher — no extra synchronization required.
        private val finalizedResults = mutableListOf<ImportResult>()

        /**
         * Enqueues [handles] for import. Replaces any current Idle or Done state.
         * If already Importing, this is a no-op — cancel first.
         */
        fun enqueueItems(
            sessionId: SessionId,
            handles: List<CameraObjectHandle>,
        ) {
            val currentState = _uiState.value
            if (currentState is ImportUiState.Importing) return

            if (handles.isEmpty()) {
                _uiState.value = ImportUiState.Idle
                return
            }

            val items = handles.map { handle -> ImportItem(handle, labelFor(handle)) }
            _uiState.value = ImportUiState.Queued(items)
            startImport(sessionId, handles)
        }

        /**
         * Cancels all queued items before import starts and resets to Idle.
         * Has no effect if import is already in progress (use [cancelItem] per item instead).
         */
        fun cancelAll() {
            if (_uiState.value is ImportUiState.Queued) {
                _uiState.value = ImportUiState.Idle
            }
        }

        /**
         * Cancels a single in-progress item identified by [transferId].
         * No-op if the transfer is unknown or already terminal.
         */
        fun cancelItem(transferId: TransferId) {
            viewModelScope.launch {
                cancelImportUseCase(transferId)
                // The flow for this item will emit ImportState.Cancelled which updates state.
            }
        }

        // ── Private helpers ────────────────────────────────────────────────────

        private fun startImport(
            sessionId: SessionId,
            handles: List<CameraObjectHandle>,
        ) {
            // Fresh import run — discard any results/ids accumulated by a prior batch.
            finalizedResults.clear()
            activeTransferIds.clear()

            // Seed the Importing state with zero-progress rows.
            val initialRows =
                handles.map { handle ->
                    ImportItemProgress(
                        objectHandle = handle,
                        transferId = TransferId(0L),
                        label = labelFor(handle),
                        bytesTransferred = 0L,
                        totalBytes = 0L,
                        fraction = 0f,
                    )
                }
            _uiState.value = ImportUiState.Importing(initialRows)

            // Launch a per-item collection job for each handle.
            handles.forEach { handle ->
                importObjectUseCase
                    .invoke(sessionId, handle)
                    .onEach { importState -> handleImportState(handle, importState) }
                    .launchIn(viewModelScope)
            }
        }

        private fun handleImportState(
            handle: CameraObjectHandle,
            importState: ImportState,
        ) {
            when (importState) {
                is ImportState.Idle -> {
                    // No-op — item not yet started.
                }

                is ImportState.Running -> {
                    val progress = importState.progress
                    activeTransferIds[progress.transferId] = handle
                    val fraction =
                        if (progress.totalBytes > 0L) {
                            (progress.bytesTransferred.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    updateItemProgress(
                        handle = handle,
                        transferId = progress.transferId,
                        bytesTransferred = progress.bytesTransferred,
                        totalBytes = progress.totalBytes,
                        fraction = fraction,
                    )
                }

                is ImportState.Imported -> {
                    activeTransferIds.entries.removeAll { it.value == handle }
                    finalizeItem(
                        handle = handle,
                        result = ImportResult.Imported(label = labelFor(handle), localUri = importState.localUri),
                    )
                }

                is ImportState.Failed -> {
                    activeTransferIds.entries.removeAll { it.value == handle }
                    finalizeItem(
                        handle = handle,
                        result = ImportResult.Failed(label = labelFor(handle), error = importState.error),
                    )
                }

                is ImportState.Cancelled -> {
                    activeTransferIds.entries.removeAll { it.value == handle }
                    finalizeItem(
                        handle = handle,
                        result = ImportResult.Cancelled(label = labelFor(handle)),
                    )
                }
            }
        }

        private fun updateItemProgress(
            handle: CameraObjectHandle,
            transferId: TransferId,
            bytesTransferred: Long,
            totalBytes: Long,
            fraction: Float,
        ) {
            _uiState.update { current ->
                if (current !is ImportUiState.Importing) return@update current
                val updated =
                    current.items.map { row ->
                        if (row.objectHandle == handle) {
                            row.copy(
                                transferId = transferId,
                                bytesTransferred = bytesTransferred,
                                totalBytes = totalBytes,
                                fraction = fraction,
                            )
                        } else {
                            row
                        }
                    }
                current.copy(items = updated)
            }
        }

        /**
         * Moves a handle from Importing to a terminal result.
         *
         * If all items are now terminal the state transitions to Done.
         * Items that are still in progress (fraction < 1 and no terminal state yet)
         * remain in Importing.
         */
        private fun finalizeItem(
            handle: CameraObjectHandle,
            result: ImportResult,
        ) {
            // Record this item's outcome so the eventual Done state carries every item's result.
            finalizedResults.add(result)

            _uiState.update { current ->
                if (current !is ImportUiState.Importing) return@update current

                val stillActive = current.items.filter { it.objectHandle != handle }
                if (stillActive.isEmpty()) {
                    // Every item has reached a terminal state — emit all accumulated results.
                    ImportUiState.Done(results = finalizedResults.toList())
                } else {
                    // At least one item is still active; keep Importing with the remaining rows.
                    current.copy(items = stillActive)
                }
            }
        }
    }

// ── Pure helpers ─────────────────────────────────────────────────────────────

/** Generates a deterministic display label from a [CameraObjectHandle]. Never a raw filename. */
internal fun labelFor(handle: CameraObjectHandle): String = "FRP_${handle.value}"

/** Formats bytes as a human-readable string (e.g. "1.2 MB"). */
internal fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
