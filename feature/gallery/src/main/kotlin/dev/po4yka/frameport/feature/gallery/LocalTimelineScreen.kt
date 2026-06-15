package dev.po4yka.frameport.feature.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.po4yka.frameport.core.designsystem.EmptyState
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import dev.po4yka.frameport.core.model.ImportSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ─── Route entry point ────────────────────────────────────────────────────────

/**
 * Public entry point consumed by [FrameportNavHost].
 *
 * [onBack] is optional: the top app bar back button is only shown when a callback is provided.
 * Navigation mutations happen in the nav host layer only — this composable does not import
 * androidx.navigation or hold a NavController.
 *
 * Compose purity contract:
 * - Does not launch coroutines, open sockets, read files, query Room/DataStore, call JNI, or
 *   import android.bluetooth / android.net.wifi / android.hardware.usb / android.media.
 */
@Composable
fun LocalTimelineRoute(
    onBack: (() -> Unit)? = null,
    viewModel: LocalTimelineViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LocalTimelineScreen(state = state, onBack = onBack)
}

// ─── Stateless screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalTimelineScreen(
    state: LocalTimelineUiState,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Timeline") },
                navigationIcon = {
                    if (onBack != null) {
                        Text(
                            text = "<",
                            modifier =
                                Modifier
                                    .padding(horizontal = 16.dp)
                                    .clickable(onClick = onBack),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            when (state) {
                LocalTimelineUiState.Loading -> TimelineLoadingContent()
                LocalTimelineUiState.Empty -> TimelineEmptyContent()
                is LocalTimelineUiState.Loaded -> TimelineLoadedContent(sessions = state.sessions)
            }
        }
    }
}

// ─── Content slots ─────────────────────────────────────────────────────────────

@Composable
private fun TimelineLoadingContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun TimelineEmptyContent(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            title = "No imports yet",
            message = "Connect a camera and import media to see your timeline here.",
        )
    }
}

@Composable
private fun TimelineLoadedContent(
    sessions: List<ImportSession>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = sessions, key = { it.sessionKey }) { session ->
            SessionCard(
                session = session,
                thumbnails = StableThumbnailUris(session.thumbnailUris),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

// ─── Session card ─────────────────────────────────────────────────────────────

/**
 * Card for a single [ImportSession]. Tapping expands or collapses the thumbnail grid.
 *
 * Stability note: [ImportSession.thumbnailUris] is a [List<String>] which the Compose compiler
 * treats as unstable. The caller wraps it in [StableThumbnailUris] so that recomposition of
 * this composable skips when only unrelated session fields change. The [session] parameter
 * itself may still trigger recomposition until [ImportSession] is annotated @Stable or the
 * project enables strong-skipping mode (M19 hardening).
 */
@Composable
private fun SessionCard(
    session: ImportSession,
    thumbnails: StableThumbnailUris,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(session.sessionKey) { mutableStateOf(false) }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SessionCardHeader(session = session)
            AnimatedVisibility(visible = expanded && thumbnails.uris.isNotEmpty()) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    ThumbnailGrid(uris = thumbnails)
                }
            }
        }
    }
}

@Composable
private fun SessionCardHeader(
    session: ImportSession,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = formatSessionDate(session.endedAtEpochMs),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "${session.objectCount} items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatBytes(session.totalBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration(session.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = session.transportLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThumbnailGrid(
    uris: StableThumbnailUris,
    modifier: Modifier = Modifier,
) {
    // Fixed-height grid: up to 4 thumbnails in a 2-column layout.
    // LazyVerticalGrid requires a fixed height when nested in a LazyColumn.
    val rowCount = (uris.uris.size + 1) / 2
    val gridHeight = (rowCount * 80).dp
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.height(gridHeight),
        contentPadding = PaddingValues(0.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        userScrollEnabled = false,
    ) {
        items(uris.uris) { uri ->
            AsyncImage(
                model = uri,
                contentDescription = "Imported media thumbnail",
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .aspectRatio(1f)
                        .size(80.dp),
            )
        }
    }
}

// ─── Utility ──────────────────────────────────────────────────────────────────

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter
        .ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

private fun formatSessionDate(epochMs: Long): String = dateFormatter.format(Instant.ofEpochMilli(epochMs))

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
        bytes == 0L -> "—"
        else -> "$bytes B"
    }

private fun formatDuration(durationMs: Long): String {
    val totalSec = durationMs / 1_000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return when {
        durationMs == 0L -> "< 1 s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

private val previewSessions =
    listOf(
        ImportSession(
            sessionKey = "session:1",
            startedAtEpochMs = 1_700_000_000_000L,
            endedAtEpochMs = 1_700_000_120_000L,
            objectCount = 42,
            totalBytes = 1_258_291_200L,
            thumbnailUris = listOf("content://media/external/images/1", "content://media/external/images/2"),
            transportLabel = "Wi-Fi",
        ),
        ImportSession(
            sessionKey = "day:2023-11-14",
            startedAtEpochMs = 1_699_900_000_000L,
            endedAtEpochMs = 1_699_901_800_000L,
            objectCount = 5,
            totalBytes = 52_428_800L,
            thumbnailUris = emptyList(),
            transportLabel = "Unknown",
        ),
    )

@Preview(showBackground = true, name = "Timeline — Loaded")
@Composable
private fun LocalTimelineScreenLoadedPreview() {
    FrameportTheme {
        LocalTimelineScreen(state = LocalTimelineUiState.Loaded(previewSessions))
    }
}

@Preview(showBackground = true, name = "Timeline — Empty")
@Composable
private fun LocalTimelineScreenEmptyPreview() {
    FrameportTheme {
        LocalTimelineScreen(state = LocalTimelineUiState.Empty)
    }
}

@Preview(showBackground = true, name = "Timeline — Loading")
@Composable
private fun LocalTimelineScreenLoadingPreview() {
    FrameportTheme {
        LocalTimelineScreen(state = LocalTimelineUiState.Loading)
    }
}
