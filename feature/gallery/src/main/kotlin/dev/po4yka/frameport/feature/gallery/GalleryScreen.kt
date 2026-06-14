package dev.po4yka.frameport.feature.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.po4yka.frameport.camera.api.CameraMediaFormat
import dev.po4yka.frameport.camera.api.CameraMediaObject
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.designsystem.EmptyState
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import dev.po4yka.frameport.core.designsystem.FrameportTokens

// ─── Public route entry point ──────────────────────────────────────────────────

/**
 * Public route composable consumed by [FrameportNavHost].
 *
 * [sessionId] — the active camera session to load media from.
 * [onImportSelected] — called with the current selection when the user taps the FAB.
 *
 * Navigation contract: this composable must NOT import androidx.navigation or NavController.
 * Navigation mutations happen in the nav host layer only.
 */
@Composable
fun GalleryRoute(
    sessionId: SessionId,
    onImportSelected: (Set<CameraObjectHandle>) -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Trigger load whenever sessionId changes.
    LaunchedEffect(sessionId) {
        viewModel.load(sessionId)
    }

    GalleryScreen(
        state = state,
        onToggleSelection = viewModel::toggleSelection,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onImportSelected = {
            val loaded = state as? GalleryUiState.Loaded ?: return@GalleryScreen
            onImportSelected(loaded.selectedHandles)
        },
        onRetry = { viewModel.load(sessionId) },
    )
}

// ─── Stateless screen ─────────────────────────────────────────────────────────

/**
 * Stateless gallery screen. All state is passed in; all actions are lambdas.
 *
 * Compose purity invariants:
 * - Does not launch coroutines, open sockets, read files, or query Room/DataStore.
 * - Does not import android.bluetooth / android.net.wifi / android.hardware.usb / android.media.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    onToggleSelection: (CameraObjectHandle) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onImportSelected: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = state as? GalleryUiState.Loaded
    val fabEnabled = loaded != null && loaded.selectionCount > 0

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text =
                            when (state) {
                                is GalleryUiState.Loaded -> {
                                    if (state.selectionCount > 0) {
                                        "${state.selectionCount} selected"
                                    } else {
                                        "Gallery (${state.items.size})"
                                    }
                                }

                                else -> {
                                    "Gallery"
                                }
                            },
                    )
                },
                actions = {
                    if (loaded != null) {
                        if (loaded.allSelected) {
                            TextButton(onClick = onClearSelection) {
                                Text("Deselect all")
                            }
                        } else {
                            TextButton(onClick = onSelectAll) {
                                Text("Select all")
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (fabEnabled) {
                ExtendedFloatingActionButton(
                    text = { Text("Import ${loaded!!.selectionCount}") },
                    icon = { Text("↓") }, // down-arrow glyph, no material-icons dep needed
                    onClick = onImportSelected,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            when (state) {
                GalleryUiState.Loading -> {
                    GalleryLoadingContent()
                }

                GalleryUiState.Empty -> {
                    GalleryEmptyContent()
                }

                is GalleryUiState.Loaded -> {
                    GalleryLoadedContent(
                        items = state.items,
                        onToggleSelection = onToggleSelection,
                    )
                }

                is GalleryUiState.Error -> {
                    GalleryErrorContent(
                        error = state.error,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }
}

// ─── Content slots ─────────────────────────────────────────────────────────────

@Composable
private fun GalleryLoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun GalleryEmptyContent(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            title = "No camera media",
            message = "Connect a camera and open a session to browse media.",
        )
    }
}

@Composable
private fun GalleryLoadedContent(
    items: List<GalleryItem>,
    onToggleSelection: (CameraObjectHandle) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(items = items, key = { it.handle.value }) { item ->
            GalleryItemRow(
                item = item,
                onToggle = { onToggleSelection(item.handle) },
            )
        }
    }
}

@Composable
private fun GalleryErrorContent(
    error: GalleryError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(
                title = "Could not load media",
                message = error.toUserMessage(),
            )
            Spacer(modifier = Modifier.size(12.dp))
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

// ─── Item row ─────────────────────────────────────────────────────────────────

@Composable
private fun GalleryItemRow(
    item: GalleryItem,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail — null ByteArray model; Coil shows nothing until thumbnails are loaded.
        // Thumbnail fetching is deferred to a future milestone; the placeholder keeps the
        // layout stable without I/O in the Composable.
        AsyncImage(
            model = null as ByteArray?,
            contentDescription = "Thumbnail for ${item.label}",
            modifier = Modifier.size(56.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
            )
            item.sizeBytes?.let { bytes ->
                Text(
                    text = formatBytes(bytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.size(4.dp))
            FormatBadge(format = item.format)
        }

        Checkbox(
            checked = item.isSelected,
            onCheckedChange = { onToggle() },
        )
    }
}

// ─── Format badge ──────────────────────────────────────────────────────────────

@Composable
private fun FormatBadge(
    format: CameraMediaFormat,
    modifier: Modifier = Modifier,
) {
    val colors =
        when (format) {
            CameraMediaFormat.Jpeg -> FrameportTokens.FormatBadgeColors.jpeg()
            CameraMediaFormat.Raf -> FrameportTokens.FormatBadgeColors.raf()
            CameraMediaFormat.Heif -> FrameportTokens.FormatBadgeColors.heif()
            CameraMediaFormat.Mov -> FrameportTokens.FormatBadgeColors.mov()
            CameraMediaFormat.Unknown -> FrameportTokens.FormatBadgeColors.unknown()
        }

    FilterChip(
        modifier = modifier,
        selected = false,
        onClick = {},
        label = {
            Text(
                text = format.badgeLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.content,
            )
        },
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = colors.container,
                labelColor = colors.content,
            ),
    )
}

// ─── Error message mapping ─────────────────────────────────────────────────────

/**
 * Maps a [GalleryError] to a human-readable string suitable for display.
 * Exhaustive — enforced by the compiler on sealed interface.
 */
private fun GalleryError.toUserMessage(): String =
    when (this) {
        GalleryError.NoSession -> {
            "No active camera session. Return to the connection screen and connect your camera."
        }

        GalleryError.TransportUnavailable -> {
            "Camera Wi-Fi network is unavailable. Ensure your device is connected to the camera network."
        }

        GalleryError.ProtocolFailure -> {
            "Camera protocol handshake failed. The camera may be busy or not supported."
        }

        is GalleryError.MediaUnavailable -> {
            "One or more media objects could not be listed. Detail: $detail"
        }

        is GalleryError.Unknown -> {
            "An unexpected error occurred. Detail: $detail"
        }
    }

// ─── Utility ──────────────────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }

// ─── Previews ─────────────────────────────────────────────────────────────────

private val previewItems: List<GalleryItem> =
    listOf(
        CameraMediaObject(
            handle = CameraObjectHandle(1001L),
            format = CameraMediaFormat.Jpeg,
            sizeBytes = 4_835_200L,
            captureDateUtcMillis = 1_700_000_000_000L,
            fileNameHash = "a1b2c3",
        ).toGalleryItem(isSelected = true),
        CameraMediaObject(
            handle = CameraObjectHandle(1002L),
            format = CameraMediaFormat.Raf,
            sizeBytes = 28_311_552L,
            captureDateUtcMillis = 1_700_000_001_000L,
            fileNameHash = "d4e5f6",
        ).toGalleryItem(isSelected = false),
        CameraMediaObject(
            handle = CameraObjectHandle(1003L),
            format = CameraMediaFormat.Heif,
            sizeBytes = 2_097_152L,
            captureDateUtcMillis = 1_700_000_002_000L,
            fileNameHash = "g7h8i9",
        ).toGalleryItem(isSelected = false),
        CameraMediaObject(
            handle = CameraObjectHandle(1004L),
            format = CameraMediaFormat.Mov,
            sizeBytes = 104_857_600L,
            captureDateUtcMillis = 1_700_000_003_000L,
            fileNameHash = "j0k1l2",
        ).toGalleryItem(isSelected = false),
        CameraMediaObject(
            handle = CameraObjectHandle(1005L),
            format = CameraMediaFormat.Unknown,
            sizeBytes = null,
            captureDateUtcMillis = null,
            fileNameHash = null,
        ).toGalleryItem(isSelected = false),
    )

@Preview(showBackground = true, name = "Gallery – Loaded (1 selected)")
@Composable
private fun GalleryScreenLoadedPreview() {
    FrameportTheme {
        GalleryScreen(
            state =
                GalleryUiState.Loaded(
                    items = previewItems,
                    selectedHandles = setOf(CameraObjectHandle(1001L)),
                ),
            onToggleSelection = {},
            onSelectAll = {},
            onClearSelection = {},
            onImportSelected = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, name = "Gallery – Empty")
@Composable
private fun GalleryScreenEmptyPreview() {
    FrameportTheme {
        GalleryScreen(
            state = GalleryUiState.Empty,
            onToggleSelection = {},
            onSelectAll = {},
            onClearSelection = {},
            onImportSelected = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, name = "Gallery – Loading")
@Composable
private fun GalleryScreenLoadingPreview() {
    FrameportTheme {
        GalleryScreen(
            state = GalleryUiState.Loading,
            onToggleSelection = {},
            onSelectAll = {},
            onClearSelection = {},
            onImportSelected = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, name = "Gallery – Error (transport unavailable)")
@Composable
private fun GalleryScreenErrorPreview() {
    FrameportTheme {
        GalleryScreen(
            state = GalleryUiState.Error(GalleryError.TransportUnavailable),
            onToggleSelection = {},
            onSelectAll = {},
            onClearSelection = {},
            onImportSelected = {},
            onRetry = {},
        )
    }
}
