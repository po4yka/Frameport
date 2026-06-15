package dev.po4yka.frameport.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.po4yka.frameport.camera.api.DiagnosticCategory
import dev.po4yka.frameport.camera.api.DiagnosticEvent
import dev.po4yka.frameport.camera.api.ErrorLayer
import dev.po4yka.frameport.camera.api.defaultCategory
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import dev.po4yka.frameport.core.designsystem.PrimaryActionButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ─── Route ────────────────────────────────────────────────────────────────────

/**
 * Navigation entry point for the Diagnostics feature. Wires hiltViewModel and
 * forwards state + callbacks to the stateless [DiagnosticsScreen].
 *
 * Keep this name stable — it is referenced by the app's NavHost.
 */
@Composable
fun DiagnosticsRoute(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect one-shot export results and show a snackbar with the file NAME only.
    // Never display the full path — privacy invariant.
    // repeatOnLifecycle(STARTED) ensures collection restarts whenever the screen
    // returns to the STARTED state (e.g. back-stack re-entry, foreground resume)
    // and cancels cleanly when the lifecycle drops below STARTED. A plain
    // LaunchedEffect(Unit) would miss events emitted while the screen was paused.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.exportResult.collect { result ->
                when (result) {
                    is ExportResult.Success -> {
                        snackbarHostState.showSnackbar(
                            message = "Exported: ${result.fileName}",
                        )
                    }

                    is ExportResult.Failure -> {
                        snackbarHostState.showSnackbar(
                            message = "Export failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        DiagnosticsScreen(
            events = events,
            onExportClick = viewModel::exportBundle,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

/**
 * Stateless Diagnostics screen.
 *
 * Renders a [LazyColumn] of [DiagnosticEvent] rows followed by an "Export Diagnostics" button.
 * Pure UI — no I/O, no JNI, no sockets.
 */
@Composable
fun DiagnosticsScreen(
    events: List<DiagnosticEvent>,
    onExportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (events.isEmpty()) {
            EmptyEventsState(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(events, key = { "${it.timestamp.toEpochMilli()}-${it.message}" }) { event ->
                    DiagnosticEventRow(event = event)
                }
            }
        }

        PrimaryActionButton(
            text = "Export Diagnostics",
            onClick = onExportClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

// ─── Event Row ────────────────────────────────────────────────────────────────

/**
 * Single-row representation of a [DiagnosticEvent].
 *
 * Renders:
 * - A coloured layer/category badge on the left.
 * - A local-formatted timestamp and the (already-redacted) message on the right.
 *
 * PRIVACY: [event.message] and [event.metadata] are pre-redacted by the time they reach here.
 * This Composable MUST NOT log, print, or otherwise re-emit any field value.
 */
@Composable
fun DiagnosticEventRow(
    event: DiagnosticEvent,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            LayerBadge(layer = event.layer, category = event.category)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = event.timestamp.formatLocal(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─── Layer Badge ──────────────────────────────────────────────────────────────

/**
 * Small coloured pill displaying the [ErrorLayer] name.
 *
 * Color is derived from [errorLayerBadgeColors], which maps each [ErrorLayer]
 * to a Material 3 color-role pair without any hardcoded hex literals.
 */
@Composable
private fun LayerBadge(
    layer: ErrorLayer,
    category: DiagnosticCategory,
    modifier: Modifier = Modifier,
) {
    val colors = errorLayerBadgeColors(layer)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = colors.container,
    ) {
        Text(
            text = layer.name,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = colors.content,
        )
    }
    // category is structurally part of the event but not rendered individually here;
    // including it as a parameter reserves space for future badge variants without signature changes.
    @Suppress("UNUSED_EXPRESSION")
    category
}

/**
 * Maps an [ErrorLayer] to a container/content [BadgeColors] pair using Material 3 color roles.
 * No hardcoded hex literals — all values come from [MaterialTheme.colorScheme].
 */
private data class BadgeColors(
    val container: Color,
    val content: Color,
)

@Composable
private fun errorLayerBadgeColors(layer: ErrorLayer): BadgeColors =
    when (layer) {
        ErrorLayer.Permission,
        ErrorLayer.UserAction,
        -> {
            BadgeColors(
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        ErrorLayer.Bluetooth,
        ErrorLayer.Wifi,
        ErrorLayer.Socket,
        ErrorLayer.Usb,
        -> {
            BadgeColors(
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        ErrorLayer.NativeBoundary,
        ErrorLayer.Transport,
        ErrorLayer.Protocol,
        -> {
            BadgeColors(
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        ErrorLayer.CameraState,
        ErrorLayer.MediaTransfer,
        -> {
            BadgeColors(
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }

        ErrorLayer.Storage,
        ErrorLayer.Location,
        ErrorLayer.Diagnostics,
        -> {
            BadgeColors(
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

// ─── Empty State ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyEventsState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        FrameportScreen {
            Text(
                text = "No diagnostic events yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Events are recorded when camera operations run. All data stays on-device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Timestamp formatting ─────────────────────────────────────────────────────

private val LOCAL_TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

private fun Instant.formatLocal(): String = LOCAL_TIMESTAMP_FORMATTER.format(this)

// ─── Previews ─────────────────────────────────────────────────────────────────

/** Synthetic stub events used only in @Preview — NEVER real device identifiers. */
private fun previewEvents(): List<DiagnosticEvent> =
    listOf(
        DiagnosticEvent(
            timestamp = Instant.parse("2026-06-14T10:00:00Z"),
            layer = ErrorLayer.Wifi,
            category = defaultCategory(ErrorLayer.Wifi),
            message = "Wi-Fi network requested",
            sessionId = "session-preview-001",
        ),
        DiagnosticEvent(
            timestamp = Instant.parse("2026-06-14T10:00:01Z"),
            layer = ErrorLayer.Protocol,
            category = defaultCategory(ErrorLayer.Protocol),
            message = "PTP-IP handshake initiated",
            sessionId = "session-preview-001",
        ),
        DiagnosticEvent(
            timestamp = Instant.parse("2026-06-14T10:00:02Z"),
            layer = ErrorLayer.Permission,
            category = defaultCategory(ErrorLayer.Permission),
            message = "NEARBY_WIFI_DEVICES permission granted",
        ),
        DiagnosticEvent(
            timestamp = Instant.parse("2026-06-14T10:00:03Z"),
            layer = ErrorLayer.MediaTransfer,
            category = defaultCategory(ErrorLayer.MediaTransfer),
            message = "Transfer complete: 1 object",
            metadata = mapOf("count" to "1"),
            sessionId = "session-preview-001",
        ),
        DiagnosticEvent(
            timestamp = Instant.parse("2026-06-14T10:00:04Z"),
            layer = ErrorLayer.NativeBoundary,
            category = defaultCategory(ErrorLayer.NativeBoundary),
            message = "JNI session closed cleanly",
        ),
    )

@Preview(showBackground = true, name = "DiagnosticsScreen — with events")
@Composable
private fun DiagnosticsScreenWithEventsPreview() {
    FrameportTheme {
        DiagnosticsScreen(
            events = previewEvents(),
            onExportClick = {},
        )
    }
}

@Preview(showBackground = true, name = "DiagnosticsScreen — empty")
@Composable
private fun DiagnosticsScreenEmptyPreview() {
    FrameportTheme {
        DiagnosticsScreen(
            events = emptyList(),
            onExportClick = {},
        )
    }
}

@Preview(showBackground = true, name = "DiagnosticEventRow — Wifi")
@Composable
private fun DiagnosticEventRowPreview() {
    FrameportTheme {
        DiagnosticEventRow(
            event =
                DiagnosticEvent(
                    timestamp = Instant.parse("2026-06-14T10:00:00Z"),
                    layer = ErrorLayer.Wifi,
                    category = defaultCategory(ErrorLayer.Wifi),
                    message = "Wi-Fi camera network bound to socket fd=42",
                    sessionId = "session-preview-001",
                ),
        )
    }
}
