package dev.po4yka.frameport.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.po4yka.frameport.camera.api.CameraMediaFormat
import dev.po4yka.frameport.core.designsystem.FrameportTheme

// ─── UI state ────────────────────────────────────────────────────────────────

/**
 * UI state for the settings screen.
 *
 * [isLoading] is true only during the initial DataStore read (before the first emission).
 * [preferences] holds the current values once loaded.
 */
data class SettingsUiState(
    val isLoading: Boolean = true,
    val preferences: ImportPreferences = ImportPreferences(),
)

// ─── Actions ─────────────────────────────────────────────────────────────────

sealed interface SettingsAction {
    /** Toggle the auto-import-on-connect switch. */
    data class SetAutoImport(
        val enabled: Boolean,
    ) : SettingsAction

    /** Toggle a single format in the multi-select filter. */
    data class ToggleFormatFilter(
        val format: CameraMediaFormat,
    ) : SettingsAction

    /** Update the import path template text. */
    data class SetImportPathTemplate(
        val template: String,
    ) : SettingsAction

    /** Toggle [ImportPreferences.preserveOriginalFilename]. */
    data class SetPreserveOriginalFilename(
        val preserve: Boolean,
    ) : SettingsAction

    /** Pull-to-refresh / manual reload (not needed with DataStore but kept for symmetry). */
    data object Refresh : SettingsAction
}

// ─── Route entry point ───────────────────────────────────────────────────────

/**
 * Public entry point wired in [FrameportNavHost].
 *
 * Navigation rule: SettingsRoute receives NO NavController and NO nav-related callbacks —
 * Settings is a leaf destination with no outbound navigation.
 */
@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onAction = viewModel::onAction,
    )
}

// ─── Screen ──────────────────────────────────────────────────────────────────

/**
 * Stateless settings screen.
 *
 * Compose purity contract:
 * - Renders [state] only; emits user actions via [onAction].
 * - Does NOT call suspend functions, launch coroutines, open sockets, read files,
 *   query Room/DataStore, call JNI, or import android.bluetooth / android.net.wifi /
 *   android.hardware.usb / android.media.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionHeader(title = "Import")

            AutoImportItem(
                checked = state.preferences.autoImportOnConnect,
                onCheckedChange = { onAction(SettingsAction.SetAutoImport(it)) },
            )

            HorizontalDivider()

            FormatFilterSection(
                selected = state.preferences.formatFilter,
                onToggle = { onAction(SettingsAction.ToggleFormatFilter(it)) },
            )

            HorizontalDivider()

            ImportPathTemplateItem(
                value = state.preferences.importPathTemplate,
                onValueChange = { onAction(SettingsAction.SetImportPathTemplate(it)) },
            )

            HorizontalDivider()

            PreserveFilenameItem(
                checked = state.preferences.preserveOriginalFilename,
                onCheckedChange = { onAction(SettingsAction.SetPreserveOriginalFilename(it)) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            SettingsSectionHeader(title = "Privacy")

            PrivacyInfoItem()

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ─── Section components ───────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun AutoImportItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier,
        headlineContent = { Text("Auto-import on connect") },
        supportingContent = {
            Text("Begin importing media as soon as a camera session is established")
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
        colors =
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatFilterSection(
    selected: Set<CameraMediaFormat>,
    onToggle: (CameraMediaFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ListItem(
            headlineContent = { Text("Format filter") },
            supportingContent = {
                Text("Select formats to import — leave all unselected to import every format")
            },
            colors =
                ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
        )
        FlowRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Unknown is intentionally excluded — it is a catch-all, not a user-selectable format.
            CameraMediaFormat.entries
                .filter { it != CameraMediaFormat.Unknown }
                .forEach { format ->
                    FilterChip(
                        selected = selected.contains(format),
                        onClick = { onToggle(format) },
                        label = { Text(format.displayName()) },
                    )
                }
        }
    }
}

@Composable
private fun ImportPathTemplateItem(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Import path template",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Relative path under DCIM. Use {date} for the import date (ISO-8601).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("DCIM/Frameport/{date}") },
        )
    }
}

@Composable
private fun PrivacyInfoItem(modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier,
        headlineContent = { Text("Local-only storage") },
        supportingContent = {
            Text(
                "Frameport stores all media and preferences on this device only. " +
                    "No cloud sync, no account, no analytics, no telemetry.",
            )
        },
        colors =
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    )
}

@Composable
private fun PreserveFilenameItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier,
        headlineContent = { Text("Preserve original filename") },
        supportingContent = {
            Text(
                "Use the camera's original filename as the MediaStore display name. " +
                    "When off, a locally-generated timestamp name is used (more private).",
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
        colors =
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    )
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Human-readable label for each [CameraMediaFormat] used in the UI only. */
private fun CameraMediaFormat.displayName(): String =
    when (this) {
        CameraMediaFormat.Jpeg -> "JPEG"
        CameraMediaFormat.Raf -> "RAF (Fujifilm RAW)"
        CameraMediaFormat.Heif -> "HEIF"
        CameraMediaFormat.Mov -> "MOV (video)"
        CameraMediaFormat.Unknown -> "Unknown / Other"
    }

// ─── Previews ────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "Settings — defaults")
@Composable
private fun SettingsScreenDefaultPreview() {
    FrameportTheme {
        SettingsScreen(
            state =
                SettingsUiState(
                    isLoading = false,
                    preferences = ImportPreferences(),
                ),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, name = "Settings — auto-import on, JPEG+RAF filter")
@Composable
private fun SettingsScreenPopulatedPreview() {
    FrameportTheme {
        SettingsScreen(
            state =
                SettingsUiState(
                    isLoading = false,
                    preferences =
                        ImportPreferences(
                            autoImportOnConnect = true,
                            formatFilter = setOf(CameraMediaFormat.Jpeg, CameraMediaFormat.Raf),
                            importPathTemplate = "DCIM/Frameport/{date}",
                        ),
                ),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, name = "Settings — loading")
@Composable
private fun SettingsScreenLoadingPreview() {
    FrameportTheme {
        SettingsScreen(
            state = SettingsUiState(isLoading = true),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, name = "Settings — Import Preferences section (all chips)")
@Composable
private fun SettingsImportPreferencesSectionPreview() {
    FrameportTheme {
        SettingsScreen(
            state =
                SettingsUiState(
                    isLoading = false,
                    preferences =
                        ImportPreferences(
                            autoImportOnConnect = true,
                            formatFilter =
                                setOf(
                                    CameraMediaFormat.Jpeg,
                                    CameraMediaFormat.Raf,
                                    CameraMediaFormat.Heif,
                                ),
                            importPathTemplate = "DCIM/Frameport/{date}/{model}",
                            preserveOriginalFilename = true,
                        ),
                ),
            onAction = {},
        )
    }
}
