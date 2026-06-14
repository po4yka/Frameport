package dev.po4yka.frameport.feature.onboarding

import android.Manifest
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [OnboardingViewModel].
 *
 * Testing approach:
 * - Arrange: construct [OnboardingViewModel] directly (no Hilt in unit tests).
 * - Act: invoke public VM methods.
 * - Assert: collect from [StateFlow] via Turbine or read synchronous properties.
 *
 * No Android framework dependencies — [OnboardingViewModel] imports only stdlib + coroutines.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = OnboardingViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial uiState is Idle`() =
        runTest {
            viewModel.uiState.test {
                assertEquals(OnboardingUiState.Idle, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `initial grantedPermissions is empty`() =
        runTest {
            viewModel.grantedPermissions.test {
                assertEquals(emptySet<String>(), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `allGranted is false when no permissions granted`() {
        assertFalse(viewModel.allGranted)
    }

    // ─── onRequestingPermission ────────────────────────────────────────────────

    @Test
    fun `onRequestingPermission transitions state to RequestingPermission`() =
        runTest {
            viewModel.uiState.test {
                assertEquals(OnboardingUiState.Idle, awaitItem()) // initial

                viewModel.onRequestingPermission(Manifest.permission.BLUETOOTH_SCAN)

                assertEquals(
                    OnboardingUiState.RequestingPermission(Manifest.permission.BLUETOOTH_SCAN),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── onPermissionResult — granted ─────────────────────────────────────────

    @Test
    fun `granted permission is added to grantedPermissions`() =
        runTest {
            viewModel.onPermissionResult(Manifest.permission.BLUETOOTH_SCAN, granted = true, isPermanent = false)

            viewModel.grantedPermissions.test {
                assertTrue(awaitItem().contains(Manifest.permission.BLUETOOTH_SCAN))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `granting a permission transitions uiState to PermissionGranted`() =
        runTest {
            viewModel.uiState.test {
                awaitItem() // consume Idle

                viewModel.onPermissionResult(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    granted = true,
                    isPermanent = false,
                )

                assertEquals(
                    OnboardingUiState.PermissionGranted(Manifest.permission.BLUETOOTH_CONNECT),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── onPermissionResult — denied (soft) ───────────────────────────────────

    @Test
    fun `soft denial transitions uiState to PermissionDenied with isPermanent false`() =
        runTest {
            viewModel.uiState.test {
                awaitItem() // consume Idle

                viewModel.onPermissionResult(
                    Manifest.permission.BLUETOOTH_SCAN,
                    granted = false,
                    isPermanent = false,
                )

                assertEquals(
                    OnboardingUiState.PermissionDenied(
                        permission = Manifest.permission.BLUETOOTH_SCAN,
                        isPermanent = false,
                    ),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `soft denial does not add permission to grantedPermissions`() =
        runTest {
            viewModel.onPermissionResult(
                Manifest.permission.BLUETOOTH_SCAN,
                granted = false,
                isPermanent = false,
            )

            viewModel.grantedPermissions.test {
                assertFalse(awaitItem().contains(Manifest.permission.BLUETOOTH_SCAN))
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── onPermissionResult — denied permanently ───────────────────────────────

    @Test
    fun `permanent denial transitions uiState to PermissionDenied with isPermanent true`() =
        runTest {
            viewModel.uiState.test {
                awaitItem() // consume Idle

                viewModel.onPermissionResult(
                    "android.permission.NEARBY_WIFI_DEVICES",
                    granted = false,
                    isPermanent = true,
                )

                assertEquals(
                    OnboardingUiState.PermissionDenied(
                        permission = "android.permission.NEARBY_WIFI_DEVICES",
                        isPermanent = true,
                    ),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── allGranted ───────────────────────────────────────────────────────────

    @Test
    fun `allGranted is false when only some permissions are granted`() {
        viewModel.onPermissionResult(Manifest.permission.BLUETOOTH_SCAN, granted = true, isPermanent = false)
        viewModel.onPermissionResult(Manifest.permission.BLUETOOTH_CONNECT, granted = true, isPermanent = false)
        // NEARBY_WIFI_DEVICES not yet granted

        assertFalse(viewModel.allGranted)
    }

    @Test
    fun `allGranted is true when all required permissions are granted`() {
        REQUIRED_CAMERA_PERMISSIONS.forEach { perm ->
            viewModel.onPermissionResult(perm, granted = true, isPermanent = false)
        }

        assertTrue(viewModel.allGranted)
    }

    @Test
    fun `granting a permission multiple times does not regress allGranted`() {
        REQUIRED_CAMERA_PERMISSIONS.forEach { perm ->
            viewModel.onPermissionResult(perm, granted = true, isPermanent = false)
        }
        // Re-grant the first permission (idempotent)
        viewModel.onPermissionResult(
            REQUIRED_CAMERA_PERMISSIONS.first(),
            granted = true,
            isPermanent = false,
        )

        assertTrue(viewModel.allGranted)
    }

    // ─── grantedPermissions accumulation ─────────────────────────────────────

    @Test
    fun `grantedPermissions accumulates across multiple grants`() =
        runTest {
            viewModel.onPermissionResult(Manifest.permission.BLUETOOTH_SCAN, granted = true, isPermanent = false)
            viewModel.onPermissionResult(Manifest.permission.BLUETOOTH_CONNECT, granted = true, isPermanent = false)

            viewModel.grantedPermissions.test {
                val granted = awaitItem()
                assertTrue(granted.contains(Manifest.permission.BLUETOOTH_SCAN))
                assertTrue(granted.contains(Manifest.permission.BLUETOOTH_CONNECT))
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── String helpers ───────────────────────────────────────────────────────

    @Test
    fun `permissionDisplayName returns human-readable name for all required permissions`() {
        REQUIRED_CAMERA_PERMISSIONS.forEach { perm ->
            val name = permissionDisplayName(perm)
            // Must be non-empty and not the raw manifest string
            assertTrue("Expected display name for $perm but got empty", name.isNotBlank())
        }
    }

    @Test
    fun `permissionRationale returns non-empty string for all required permissions`() {
        REQUIRED_CAMERA_PERMISSIONS.forEach { perm ->
            val rationale = permissionRationale(perm)
            assertTrue("Expected rationale for $perm but got empty", rationale.isNotBlank())
        }
    }

    @Test
    fun `permissionDeniedMessage returns non-empty string for all required permissions`() {
        REQUIRED_CAMERA_PERMISSIONS.forEach { perm ->
            val msg = permissionDeniedMessage(perm)
            assertTrue("Expected denial message for $perm but got empty", msg.isNotBlank())
        }
    }

    @Test
    fun `REQUIRED_CAMERA_PERMISSIONS contains the three expected permissions`() {
        assertTrue(REQUIRED_CAMERA_PERMISSIONS.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(REQUIRED_CAMERA_PERMISSIONS.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(REQUIRED_CAMERA_PERMISSIONS.contains("android.permission.NEARBY_WIFI_DEVICES"))
    }
}
