package dev.po4yka.frameport.feature.importmedia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.TransferId
import dev.po4yka.frameport.core.designsystem.EmptyState
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import dev.po4yka.frameport.core.model.FrameportError

// ─── Route entry point ────────────────────────────────────────────────────────

/**
 * Public entry point consumed by [FrameportNavHost].
 * Receives no NavController — navigation callbacks are passed as lambdas.
 */
@Composable
fun ImportRoute(viewModel: ImportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ImportScreen(
        state = state,
        onCancelAll = viewModel::cancelAll,
        onCancelItem = viewModel::cancelItem,
    )
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@Composable
fun ImportScreen(
    state: ImportUiState,
    onCancelAll: () -> Unit,
    onCancelItem: (TransferId) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        ImportUiState.Idle -> {
            ImportIdleContent(modifier = modifier)
        }

        is ImportUiState.Queued -> {
            ImportQueuedContent(
                queue = state.queue,
                onCancelAll = onCancelAll,
                modifier = modifier,
            )
        }

        is ImportUiState.Importing -> {
            ImportingContent(
                items = state.items,
                onCancelItem = onCancelItem,
                modifier = modifier,
            )
        }

        is ImportUiState.Done -> {
            ImportDoneContent(
                results = state.results,
                modifier = modifier,
            )
        }
    }
}

// ─── Idle ─────────────────────────────────────────────────────────────────────

@Composable
private fun ImportIdleContent(modifier: Modifier = Modifier) {
    FrameportScreen(modifier = modifier) {
        EmptyState(
            title = "Nothing to import",
            message = "Select images from the gallery to start importing.",
        )
    }
}

// ─── Queued ───────────────────────────────────────────────────────────────────

@Composable
private fun ImportQueuedContent(
    queue: List<ImportItem>,
    onCancelAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FrameportScreen(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${queue.size} item${if (queue.size == 1) "" else "s"} queued",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = onCancelAll) {
                Text("Cancel All")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(queue, key = { it.objectHandle.value }) { item ->
                ImportProgressRow(
                    label = item.label,
                    status = ImportItemStatus.Queued,
                    fraction = 0f,
                    bytesTransferred = 0L,
                    totalBytes = 0L,
                    onCancel = null,
                )
            }
        }
    }
}

// ─── Importing ────────────────────────────────────────────────────────────────

@Composable
private fun ImportingContent(
    items: List<ImportItemProgress>,
    onCancelItem: (TransferId) -> Unit,
    modifier: Modifier = Modifier,
) {
    FrameportScreen(modifier = modifier) {
        Text(
            text = "Importing ${items.size} item${if (items.size == 1) "" else "s"}…",
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.objectHandle.value }) { item ->
                ImportProgressRow(
                    label = item.label,
                    status = ImportItemStatus.Importing,
                    fraction = item.fraction,
                    bytesTransferred = item.bytesTransferred,
                    totalBytes = item.totalBytes,
                    onCancel = { onCancelItem(item.transferId) },
                )
            }
        }
    }
}

// ─── Done ─────────────────────────────────────────────────────────────────────

@Composable
private fun ImportDoneContent(
    results: List<ImportResult>,
    modifier: Modifier = Modifier,
) {
    val imported = results.count { it is ImportResult.Imported }
    val failed = results.count { it is ImportResult.Failed }
    val cancelled = results.count { it is ImportResult.Cancelled }

    FrameportScreen(modifier = modifier) {
        Text(
            text = "Import complete",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "$imported imported • $failed failed • $cancelled cancelled",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.label }) { result ->
                val status =
                    when (result) {
                        is ImportResult.Imported -> ImportItemStatus.Done
                        is ImportResult.Failed -> ImportItemStatus.Failed(result.error)
                        is ImportResult.Cancelled -> ImportItemStatus.Cancelled
                    }
                ImportProgressRow(
                    label = result.label,
                    status = status,
                    fraction = if (result is ImportResult.Imported) 1f else 0f,
                    bytesTransferred = 0L,
                    totalBytes = 0L,
                    onCancel = null,
                )
            }
        }
    }
}

// ─── Row ──────────────────────────────────────────────────────────────────────

/** Discriminant driving the status chip and cancel button visibility on each row. */
sealed interface ImportItemStatus {
    data object Queued : ImportItemStatus

    data object Importing : ImportItemStatus

    data object Done : ImportItemStatus

    data class Failed(
        val error: FrameportError,
    ) : ImportItemStatus

    data object Cancelled : ImportItemStatus
}

/**
 * Single row displaying one import item: label, progress bar, byte counts, status chip.
 *
 * [onCancel] is non-null only while the item is actively Importing.
 * This is a pure Composable — no I/O, no coroutines, no suspend calls.
 */
@Composable
fun ImportProgressRow(
    label: String,
    status: ImportItemStatus,
    fraction: Float,
    bytesTransferred: Long,
    totalBytes: Long,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            ImportStatusChip(status = status)
            if (onCancel != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onCancel,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) {
                    Text("Cancel")
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )

        if (totalBytes > 0L) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${formatBytes(bytesTransferred)} / ${formatBytes(totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportStatusChip(
    status: ImportItemStatus,
    modifier: Modifier = Modifier,
) {
    val label =
        when (status) {
            ImportItemStatus.Queued -> "Queued"
            ImportItemStatus.Importing -> "Importing"
            ImportItemStatus.Done -> "Done"
            is ImportItemStatus.Failed -> importErrorLabel(status.error)
            ImportItemStatus.Cancelled -> "Cancelled"
        }
    SuggestionChip(
        modifier = modifier,
        onClick = {},
        label = { Text(text = label, style = MaterialTheme.typography.labelSmall) },
    )
}

/**
 * Maps a [FrameportError] to a human-readable chip label shown on a failed row.
 * All arms are covered; no raw exception strings reach the UI.
 */
private fun importErrorLabel(error: FrameportError): String =
    when (error) {
        is FrameportError.PermissionDenied -> "Permission denied"
        is FrameportError.TransportUnavailable -> "Transport unavailable"
        is FrameportError.ProtocolUnavailable -> "Protocol error"
        is FrameportError.MediaUnavailable -> "Media unavailable"
        is FrameportError.Unknown -> "Failed"
    }

// ─── Previews ─────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "Import — Idle")
@Composable
private fun ImportIdlePreview() {
    FrameportTheme {
        ImportScreen(
            state = ImportUiState.Idle,
            onCancelAll = {},
            onCancelItem = {},
        )
    }
}

@Preview(showBackground = true, name = "Import — Mixed queue")
@Composable
private fun ImportMixedPreview() {
    FrameportTheme {
        ImportScreen(
            state =
                ImportUiState.Importing(
                    items =
                        listOf(
                            ImportItemProgress(
                                objectHandle = CameraObjectHandle(1001L),
                                transferId = TransferId(101L),
                                label = "FRP_1001",
                                bytesTransferred = 2_048_000L,
                                totalBytes = 5_120_000L,
                                fraction = 0.4f,
                            ),
                            ImportItemProgress(
                                objectHandle = CameraObjectHandle(1002L),
                                transferId = TransferId(102L),
                                label = "FRP_1002",
                                bytesTransferred = 3_145_728L,
                                totalBytes = 3_145_728L,
                                fraction = 1f,
                            ),
                            ImportItemProgress(
                                objectHandle = CameraObjectHandle(1003L),
                                transferId = TransferId(0L),
                                label = "FRP_1003",
                                bytesTransferred = 0L,
                                totalBytes = 0L,
                                fraction = 0f,
                            ),
                        ),
                ),
            onCancelAll = {},
            onCancelItem = {},
        )
    }
}

@Preview(showBackground = true, name = "Import — Done")
@Composable
private fun ImportDonePreview() {
    FrameportTheme {
        ImportScreen(
            state =
                ImportUiState.Done(
                    results =
                        listOf(
                            ImportResult.Imported(
                                label = "FRP_1001",
                                localUri = "content://media/external/images/1001",
                            ),
                            ImportResult.Cancelled(label = "FRP_1002"),
                            ImportResult.Failed(
                                label = "FRP_1003",
                                error =
                                    FrameportError.MediaUnavailable(
                                        mediaObjectId = null,
                                        message = "Object not found",
                                    ),
                            ),
                        ),
                ),
            onCancelAll = {},
            onCancelItem = {},
        )
    }
}

@Preview(showBackground = true, name = "Import — Progress row importing")
@Composable
private fun ImportProgressRowImportingPreview() {
    FrameportTheme {
        ImportProgressRow(
            label = "FRP_1001",
            status = ImportItemStatus.Importing,
            fraction = 0.4f,
            bytesTransferred = 2_048_000L,
            totalBytes = 5_120_000L,
            onCancel = {},
        )
    }
}

@Preview(showBackground = true, name = "Import — Progress row done")
@Composable
private fun ImportProgressRowDonePreview() {
    FrameportTheme {
        ImportProgressRow(
            label = "FRP_1002",
            status = ImportItemStatus.Done,
            fraction = 1f,
            bytesTransferred = 3_145_728L,
            totalBytes = 3_145_728L,
            onCancel = null,
        )
    }
}
