package dev.po4yka.frameport.feature.onboarding

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

// ─── Required permissions ─────────────────────────────────────────────────────

/** Permissions the app requires before any camera operation can proceed. */
val REQUIRED_CAMERA_PERMISSIONS: List<String> =
    listOf(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        "android.permission.NEARBY_WIFI_DEVICES",
    )

// ─── UI state ─────────────────────────────────────────────────────────────────

/**
 * Fine-grained per-permission state exposed by [OnboardingViewModel].
 *
 * [Idle] — initial state; no request in flight yet.
 * [RequestingPermission] — the system rationale dialog or launcher was triggered.
 * [PermissionGranted] — user granted the named permission.
 * [PermissionDenied] — user denied; [isPermanent] is true when "Don't ask again" was chosen.
 */
sealed interface OnboardingUiState {
    data object Idle : OnboardingUiState

    data class RequestingPermission(
        val permission: String,
    ) : OnboardingUiState

    data class PermissionGranted(
        val permission: String,
    ) : OnboardingUiState

    data class PermissionDenied(
        val permission: String,
        val isPermanent: Boolean,
    ) : OnboardingUiState
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

/**
 * Manages permission grant state for the onboarding flow.
 *
 * Architecture invariants:
 * - No Android framework APIs are imported here (ContextCompat, PackageManager, etc.).
 *   The Composable drives all launcher activity via [rememberLauncherForActivityResult];
 *   results are reported back through [onPermissionResult].
 * - No :core:permissions dependency — self-contained per GT-PERMISSIONS.
 * - [uiState] has a meaningful initial value ([OnboardingUiState.Idle]); never uninitialized.
 * - [allGranted] is a pure derived property; no extra StateFlow allocation needed.
 * - [grantedPermissions] is the single source of truth; [uiState] reflects the last event.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor() : ViewModel() {
        private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
        val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

        /** Tracks which required permissions have been granted so far. */
        private val _grantedPermissions = MutableStateFlow<Set<String>>(emptySet())
        val grantedPermissions: StateFlow<Set<String>> = _grantedPermissions.asStateFlow()

        /** True only when every permission in [REQUIRED_CAMERA_PERMISSIONS] has been granted. */
        val allGranted: Boolean
            get() = _grantedPermissions.value.containsAll(REQUIRED_CAMERA_PERMISSIONS)

        // ─── Actions called by the Composable ─────────────────────────────────────

        /**
         * Signal that the launcher for [permission] is about to be shown.
         * The Composable calls this just before invoking the launcher so the UI
         * can transition to [OnboardingUiState.RequestingPermission].
         */
        fun onRequestingPermission(permission: String) {
            _uiState.value = OnboardingUiState.RequestingPermission(permission)
        }

        /**
         * Report the result of a single permission grant dialog.
         *
         * @param permission  The manifest permission string (e.g. [android.Manifest.permission.BLUETOOTH_SCAN]).
         * @param granted     True when the user granted the permission.
         * @param isPermanent True when the user chose "Don't ask again" (permanently denied).
         *                    The Composable determines this by checking
         *                    [ActivityCompat.shouldShowRequestPermissionRationale] AFTER the
         *                    launcher returns false — it is UI-layer logic, not ViewModel logic.
         */
        fun onPermissionResult(
            permission: String,
            granted: Boolean,
            isPermanent: Boolean,
        ) {
            if (granted) {
                _grantedPermissions.value = _grantedPermissions.value + permission
                _uiState.value = OnboardingUiState.PermissionGranted(permission)
            } else {
                _uiState.value =
                    OnboardingUiState.PermissionDenied(
                        permission = permission,
                        isPermanent = isPermanent,
                    )
            }
        }
    }
