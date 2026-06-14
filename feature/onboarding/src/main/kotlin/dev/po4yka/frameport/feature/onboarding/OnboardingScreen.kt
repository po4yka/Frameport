package dev.po4yka.frameport.feature.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.po4yka.frameport.core.designsystem.FrameportScreen
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import dev.po4yka.frameport.core.designsystem.PrimaryActionButton

// ─── Route (nav host entry point) ────────────────────────────────────────────

/**
 * Public entry point consumed by [FrameportNavHost].
 *
 * Navigation contract: receives [onContinue] lambda only — no NavController import.
 */
@Composable
fun OnboardingRoute(
    onContinue: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val grantedPermissions by viewModel.grantedPermissions.collectAsStateWithLifecycle()

    OnboardingScreen(
        uiState = uiState,
        grantedPermissions = grantedPermissions,
        requiredPermissions = REQUIRED_CAMERA_PERMISSIONS,
        onRequestingPermission = viewModel::onRequestingPermission,
        onPermissionResult = viewModel::onPermissionResult,
        onContinue = onContinue,
    )
}

// ─── Screen ───────────────────────────────────────────────────────────────────

/**
 * Stateless screen that renders onboarding permission rationale cards and drives
 * the system permission launcher.
 *
 * Compose purity invariants:
 * - No suspend calls, no I/O, no Room/DataStore, no JNI, no Bluetooth/Wi-Fi/USB APIs.
 * - [rememberLauncherForActivityResult] is UI interaction scaffolding (allowed).
 * - [ActivityCompat.shouldShowRequestPermissionRationale] is a pure query on the
 *   Activity context for determining permanent denial — read-only, no side-effects.
 * - All state comes in via parameters; all actions go out via lambdas.
 */
@Composable
fun OnboardingScreen(
    uiState: OnboardingUiState,
    grantedPermissions: Set<String>,
    requiredPermissions: List<String>,
    onRequestingPermission: (permission: String) -> Unit,
    onPermissionResult: (permission: String, granted: Boolean, isPermanent: Boolean) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // One shared launcher that requests a single permission at a time.
    // Must survive recomposition: the launcher's result callback fires after the system dialog,
    // by which point the composable has recomposed — a plain var would have reset to "" and the
    // result would be dropped. remember { mutableStateOf } keeps the in-flight permission stable.
    var pendingPermission by remember { mutableStateOf("") }
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val perm = pendingPermission
            if (perm.isNotEmpty()) {
                val isPermanent =
                    !granted &&
                        context is android.app.Activity &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(context, perm)
                onPermissionResult(perm, granted, isPermanent)
            }
        }

    FrameportScreen(modifier = modifier) {
        // Header
        Text(
            text = "Frameport",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Grant the permissions below so Frameport can discover and connect to your Fujifilm camera.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // One card per required permission
        requiredPermissions.forEach { permission ->
            val isGranted = permission in grantedPermissions
            val isDeniedPermanently =
                uiState is OnboardingUiState.PermissionDenied &&
                    uiState.permission == permission &&
                    uiState.isPermanent

            PermissionRationaleCard(
                permission = permission,
                isGranted = isGranted,
                isDeniedPermanently = isDeniedPermanently,
                onGrant = {
                    pendingPermission = permission
                    onRequestingPermission(permission)
                    launcher.launch(permission)
                },
                onOpenSettings = {
                    // Open app system settings so the user can manually grant.
                    // This is a pure Intent navigation — no I/O or suspend.
                    val intent =
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null),
                        )
                    context.startActivity(intent)
                },
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Error / status banner (shown when a non-permanent denial just occurred)
        if (uiState is OnboardingUiState.PermissionDenied && !uiState.isPermanent) {
            PermissionDeniedBanner(
                message = permissionDeniedMessage(uiState.permission),
            )
        }

        // Continue button — enabled only when every permission is granted
        val allGranted = requiredPermissions.all { it in grantedPermissions }
        PrimaryActionButton(
            text = "Continue",
            onClick = onContinue,
            enabled = allGranted,
        )
    }
}

// ─── Permission rationale card ────────────────────────────────────────────────

@Composable
private fun PermissionRationaleCard(
    permission: String,
    isGranted: Boolean,
    isDeniedPermanently: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        when {
            isGranted -> MaterialTheme.colorScheme.primaryContainer
            isDeniedPermanently -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainer
        }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = permissionDisplayName(permission),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color =
                    when {
                        isGranted -> MaterialTheme.colorScheme.onPrimaryContainer
                        isDeniedPermanently -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    },
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = permissionRationale(permission),
                style = MaterialTheme.typography.bodySmall,
                color =
                    when {
                        isGranted -> MaterialTheme.colorScheme.onPrimaryContainer
                        isDeniedPermanently -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    isGranted -> {
                        Text(
                            text = "Granted",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    isDeniedPermanently -> {
                        OutlinedButton(
                            onClick = onOpenSettings,
                            colors =
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                        ) {
                            Text("Open Settings")
                        }
                    }

                    else -> {
                        Button(onClick = onGrant) {
                            Text("Grant")
                        }
                    }
                }
            }
        }
    }
}

// ─── Denial banner ────────────────────────────────────────────────────────────

@Composable
private fun PermissionDeniedBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

// ─── String helpers (pure functions, no Android context) ──────────────────────

internal fun permissionDisplayName(permission: String): String =
    when (permission) {
        Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth Scan"
        Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth Connect"
        "android.permission.NEARBY_WIFI_DEVICES" -> "Nearby Wi-Fi Devices"
        else -> permission.substringAfterLast('.')
    }

internal fun permissionRationale(permission: String): String =
    when (permission) {
        Manifest.permission.BLUETOOTH_SCAN -> "Required to discover your Fujifilm camera over Bluetooth LE so Frameport can assist with Wi-Fi handoff."
        Manifest.permission.BLUETOOTH_CONNECT -> "Required to open a GATT connection to the camera and read its BLE-advertised capabilities."
        "android.permission.NEARBY_WIFI_DEVICES" -> "Required to scan for and connect to the camera's Wi-Fi network for PTP-IP image transfer."
        else -> "Required for camera connectivity."
    }

internal fun permissionDeniedMessage(permission: String): String =
    when (permission) {
        Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth Scan was denied. Camera discovery via Bluetooth LE is unavailable."
        Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth Connect was denied. BLE-assisted Wi-Fi handoff is unavailable."
        "android.permission.NEARBY_WIFI_DEVICES" -> "Nearby Wi-Fi Devices was denied. Camera Wi-Fi connection is unavailable."
        else -> "A required permission was denied. Some camera features may be unavailable."
    }

// ─── Previews ─────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "Onboarding — Idle (no permissions granted)")
@Composable
private fun OnboardingScreenIdlePreview() {
    FrameportTheme {
        OnboardingScreen(
            uiState = OnboardingUiState.Idle,
            grantedPermissions = emptySet(),
            requiredPermissions = REQUIRED_CAMERA_PERMISSIONS,
            onRequestingPermission = {},
            onPermissionResult = { _, _, _ -> },
            onContinue = {},
        )
    }
}

@Preview(showBackground = true, name = "Onboarding — BLE Connect permanently denied")
@Composable
private fun OnboardingScreenPermDeniedPreview() {
    FrameportTheme {
        OnboardingScreen(
            uiState =
                OnboardingUiState.PermissionDenied(
                    permission = Manifest.permission.BLUETOOTH_CONNECT,
                    isPermanent = true,
                ),
            grantedPermissions = setOf(Manifest.permission.BLUETOOTH_SCAN),
            requiredPermissions = REQUIRED_CAMERA_PERMISSIONS,
            onRequestingPermission = {},
            onPermissionResult = { _, _, _ -> },
            onContinue = {},
        )
    }
}

@Preview(showBackground = true, name = "Onboarding — All permissions granted")
@Composable
private fun OnboardingScreenAllGrantedPreview() {
    FrameportTheme {
        OnboardingScreen(
            uiState =
                OnboardingUiState.PermissionGranted(
                    permission = "android.permission.NEARBY_WIFI_DEVICES",
                ),
            grantedPermissions = REQUIRED_CAMERA_PERMISSIONS.toSet(),
            requiredPermissions = REQUIRED_CAMERA_PERMISSIONS,
            onRequestingPermission = {},
            onPermissionResult = { _, _, _ -> },
            onContinue = {},
        )
    }
}
