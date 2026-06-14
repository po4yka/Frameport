package dev.po4yka.frameport.feature.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.designsystem.FrameportCard
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import dev.po4yka.frameport.core.designsystem.FrameportTokens
import dev.po4yka.frameport.core.designsystem.PrimaryActionButton
import dev.po4yka.frameport.core.designsystem.StatusPill

// ─── Route entry points (owned by :app nav host) ──────────────────────────────

/**
 * Route composable for the BLE-scan → manual-connect flow.
 * Receives typed navigation callbacks only — no NavController import.
 */
@Composable
fun CameraScanRoute(
    onConnectManually: () -> Unit,
    onCancel: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                ConnectionNavEvent.Cancelled -> onCancel()
            }
        }
    }
    // In the scan route the user lands here from BLE; offer manual entry as the
    // primary action (BLE handoff is a later milestone).
    ConnectionScreen(
        state = state,
        onSsidChanged = viewModel::onSsidChanged,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onRetry = viewModel::retry,
        onCancel = {
            viewModel.cancel()
            onConnectManually()
        },
    )
}

/**
 * Route composable for the manual Wi-Fi connection entry point.
 * Receives typed navigation callbacks only — no NavController import.
 */
@Composable
fun CameraConnectRoute(
    onCancel: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                ConnectionNavEvent.Cancelled -> onCancel()
            }
        }
    }
    ConnectionScreen(
        state = state,
        onSsidChanged = viewModel::onSsidChanged,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onRetry = viewModel::retry,
        onCancel = viewModel::cancel,
    )
}

// ─── Pure screen Composable ───────────────────────────────────────────────────

/**
 * Stateless connection screen.
 *
 * Composable purity contract:
 * - Renders [state] and emits user actions via lambdas only.
 * - No suspend calls, no coroutine launches, no I/O of any kind.
 * - [rememberSaveable] SSID field state is the only local state; it is restored
 *   across process death automatically.
 */
@Composable
fun ConnectionScreen(
    state: ConnectionUiState,
    onSsidChanged: (String) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var ssidDraft by rememberSaveable { mutableStateOf("") }

    FrameportScreen(modifier = modifier) {
        // ── SSID input ───────────────────────────────────────────────────────
        OutlinedTextField(
            value = ssidDraft,
            onValueChange = { draft ->
                ssidDraft = draft
                onSsidChanged(draft)
            },
            label = { Text("Camera Wi-Fi SSID") },
            placeholder = { Text("e.g. FUJIFILM-X-T5-ABCD") },
            singleLine = true,
            enabled =
                state is ConnectionUiState.Idle ||
                    state is ConnectionUiState.EnteringCredentials ||
                    state is ConnectionUiState.Error,
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Session state chip ───────────────────────────────────────────────
        SessionStateRow(state = state)

        // ── Error card ───────────────────────────────────────────────────────
        if (state is ConnectionUiState.Error) {
            ConnectionErrorCard(
                error = state.connectionError,
                onRetry = onRetry,
            )
        }

        // ── Primary action ───────────────────────────────────────────────────
        when (state) {
            is ConnectionUiState.Idle,
            is ConnectionUiState.EnteringCredentials,
            -> {
                PrimaryActionButton(
                    text = "Connect",
                    onClick = { onConnect(ssidDraft) },
                    enabled = ssidDraft.isNotBlank(),
                )
            }

            is ConnectionUiState.Connecting -> {
                PrimaryActionButton(
                    text = "Connecting…",
                    onClick = {},
                    enabled = false,
                )
            }

            is ConnectionUiState.Connected -> {
                PrimaryActionButton(
                    text = "Disconnect",
                    onClick = onDisconnect,
                )
            }

            is ConnectionUiState.Disconnecting -> {
                PrimaryActionButton(
                    text = "Disconnecting…",
                    onClick = {},
                    enabled = false,
                )
            }

            is ConnectionUiState.Error -> {
                PrimaryActionButton(
                    text = "Retry",
                    onClick = onRetry,
                )
            }
        }

        // ── Cancel ───────────────────────────────────────────────────────────
        if (state !is ConnectionUiState.Disconnecting) {
            PrimaryActionButton(
                text = "Cancel",
                onClick = onCancel,
                enabled = state !is ConnectionUiState.Connecting,
            )
        }

        // ── Connected session info ───────────────────────────────────────────
        if (state is ConnectionUiState.Connected) {
            ConnectedSessionCard(ssid = state.ssid, sessionId = state.sessionId)
        }
    }
}

// ─── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun SessionStateRow(
    state: ConnectionUiState,
    modifier: Modifier = Modifier,
) {
    val (label, colors) =
        when (state) {
            ConnectionUiState.Idle -> "Idle" to FrameportTokens.SessionStateColors.idle()
            is ConnectionUiState.EnteringCredentials -> "Ready to connect" to FrameportTokens.SessionStateColors.idle()
            is ConnectionUiState.Connecting -> "Connecting" to FrameportTokens.SessionStateColors.connecting()
            is ConnectionUiState.Connected -> "Connected" to FrameportTokens.SessionStateColors.ready()
            ConnectionUiState.Disconnecting -> "Disconnecting" to FrameportTokens.SessionStateColors.connecting()
            is ConnectionUiState.Error -> "Error" to FrameportTokens.SessionStateColors.failed()
        }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Status:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusPill(label = label)
    }
}

@Composable
private fun ConnectedSessionCard(
    ssid: String,
    sessionId: SessionId,
    modifier: Modifier = Modifier,
) {
    FrameportCard(
        modifier = modifier,
        title = "Active session",
        subtitle = "Camera network: $ssid",
    ) {
        Text(
            text = "Session ID: ${sessionId.value}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Error card that maps every [ConnectionError] family to a human-readable string
 * and offers a retry action where the error is recoverable.
 *
 * Every branch is covered — adding a new [ConnectionError] variant will cause an
 * exhaustive-when compile error here, surfacing it immediately.
 */
@Composable
private fun ConnectionErrorCard(
    error: ConnectionError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameportTokens.ErrorSeverityColors.blocking()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.container),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Connection failed",
                style = MaterialTheme.typography.titleSmall,
                color = colors.content,
            )
            Text(
                text = errorMessage(error),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.content,
            )
            if (isRetryable(error)) {
                TextButton(onClick = onRetry) {
                    Text(text = "Retry", color = colors.content)
                }
            }
        }
    }
}

/**
 * Pure mapping from [ConnectionError] to a localized human-readable string.
 * No Android resources — plain string constants suitable for a standalone preview.
 */
@Composable
private fun errorMessage(error: ConnectionError): String =
    when (error) {
        ConnectionError.Permission.BluetoothScan -> {
            "Bluetooth Scan permission is required to discover the camera. " +
                "Grant it in Settings > App permissions."
        }

        ConnectionError.Permission.BluetoothConnect -> {
            "Bluetooth Connect permission is required to pair with the camera. " +
                "Grant it in Settings > App permissions."
        }

        ConnectionError.Permission.NearbyWifiDevices -> {
            "Nearby Wi-Fi Devices permission is required to join the camera's network. " +
                "Grant it in Settings > App permissions."
        }

        is ConnectionError.Permission.Other -> {
            "A required permission was denied: ${error.permission}. " +
                "Grant it in Settings > App permissions."
        }

        ConnectionError.Wifi.NetworkUnavailable -> {
            "The camera's Wi-Fi network was not found. Make sure the camera Wi-Fi is enabled " +
                "and the SSID is correct, then try again."
        }

        ConnectionError.Wifi.UserRejected -> {
            "The Wi-Fi network request was dismissed. Tap Retry to request it again."
        }

        ConnectionError.Wifi.SocketBindFailed -> {
            "The app could not bind a socket to the camera network. " +
                "Try turning camera Wi-Fi off and on, then retry."
        }

        ConnectionError.Protocol.HandshakeRejected -> {
            "The camera rejected the PTP/IP handshake. " +
                "Ensure no other app is connected to the camera, then retry."
        }

        ConnectionError.Protocol.CameraBusy -> {
            "The camera is busy — another connection may be active. " +
                "Disconnect other apps and retry."
        }

        is ConnectionError.Unknown -> {
            "An unexpected error occurred: ${error.message}"
        }
    }

/** Returns true for errors where an immediate retry is sensible without user action. */
private fun isRetryable(error: ConnectionError): Boolean =
    when (error) {
        is ConnectionError.Permission -> false

        // user must go to Settings
        ConnectionError.Wifi.NetworkUnavailable -> true

        ConnectionError.Wifi.UserRejected -> true

        ConnectionError.Wifi.SocketBindFailed -> true

        ConnectionError.Protocol.HandshakeRejected -> true

        ConnectionError.Protocol.CameraBusy -> true

        is ConnectionError.Unknown -> true
    }

// ─── Previews ─────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "Connection — Idle")
@Composable
private fun ConnectionScreenIdlePreview() {
    FrameportTheme {
        ConnectionScreen(
            state = ConnectionUiState.Idle,
            onSsidChanged = {},
            onConnect = {},
            onDisconnect = {},
            onRetry = {},
            onCancel = {},
        )
    }
}

@Preview(showBackground = true, name = "Connection — Connected")
@Composable
private fun ConnectionScreenConnectedPreview() {
    FrameportTheme {
        ConnectionScreen(
            state =
                ConnectionUiState.Connected(
                    sessionId = SessionId(42L),
                    ssid = "FUJIFILM-X-T5-ABCD",
                ),
            onSsidChanged = {},
            onConnect = {},
            onDisconnect = {},
            onRetry = {},
            onCancel = {},
        )
    }
}

@Preview(showBackground = true, name = "Connection — Error (Wifi.NetworkUnavailable)")
@Composable
private fun ConnectionScreenWifiErrorPreview() {
    FrameportTheme {
        ConnectionScreen(
            state =
                ConnectionUiState.Error(
                    connectionError = ConnectionError.Wifi.NetworkUnavailable,
                    ssid = "FUJIFILM-X-T5-ABCD",
                ),
            onSsidChanged = {},
            onConnect = {},
            onDisconnect = {},
            onRetry = {},
            onCancel = {},
        )
    }
}

@Preview(showBackground = true, name = "Connection — Error (Permission.BluetoothScan)")
@Composable
private fun ConnectionScreenPermissionErrorPreview() {
    FrameportTheme {
        ConnectionScreen(
            state =
                ConnectionUiState.Error(
                    connectionError = ConnectionError.Permission.BluetoothScan,
                    ssid = "FUJIFILM-X-T5-ABCD",
                ),
            onSsidChanged = {},
            onConnect = {},
            onDisconnect = {},
            onRetry = {},
            onCancel = {},
        )
    }
}

@Preview(showBackground = true, name = "Connection — Connecting")
@Composable
private fun ConnectionScreenConnectingPreview() {
    FrameportTheme {
        ConnectionScreen(
            state = ConnectionUiState.Connecting(ssid = "FUJIFILM-X-T5-ABCD"),
            onSsidChanged = {},
            onConnect = {},
            onDisconnect = {},
            onRetry = {},
            onCancel = {},
        )
    }
}
