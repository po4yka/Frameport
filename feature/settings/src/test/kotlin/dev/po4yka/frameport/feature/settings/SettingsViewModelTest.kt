package dev.po4yka.frameport.feature.settings

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.CameraMediaFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SettingsViewModel].
 *
 * Test structure: Arrange-Act-Assert (AAA).
 * Each test drives [FakeSettingsRepository] and observes [SettingsViewModel.uiState] via Turbine.
 *
 * Dispatcher: [StandardTestDispatcher] — all coroutines run on a controlled virtual clock,
 * making time-sensitive [SharingStarted.WhileSubscribed] deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeSettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        fakeRepo = FakeSettingsRepository()
        viewModel = SettingsViewModel(repository = fakeRepo)
    }

    // ─── Initial state ────────────────────────────────────────────────────────

    @Test
    fun `initial uiState is loading`() =
        runTest(testDispatcher) {
            // The StateFlow starts with isLoading = true before the first repo emission.
            assertTrue(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `uiState reflects repository preferences once collected`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                // isLoading = true sentinel emitted first
                val loading = awaitItem()
                assertTrue(loading.isLoading)

                // first repo emission arrives
                advanceUntilIdle()
                val loaded = awaitItem()

                assertFalse(loaded.isLoading)
                assertEquals(ImportPreferences(), loaded.preferences)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── SetAutoImport ────────────────────────────────────────────────────────

    @Test
    fun `SetAutoImport true writes to repository`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                awaitItem() // loading
                advanceUntilIdle()
                awaitItem() // defaults loaded

                viewModel.onAction(SettingsAction.SetAutoImport(true))
                advanceUntilIdle()

                val updated = awaitItem()
                assertTrue(updated.preferences.autoImportOnConnect)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `SetAutoImport false disables auto-import`() =
        runTest(testDispatcher) {
            // Arrange: start with auto-import enabled
            fakeRepo.setAutoImportOnConnect(true)

            viewModel.uiState.test {
                awaitItem() // loading
                advanceUntilIdle()
                val initial = awaitItem()
                assertTrue(initial.preferences.autoImportOnConnect)

                viewModel.onAction(SettingsAction.SetAutoImport(false))
                advanceUntilIdle()

                val updated = awaitItem()
                assertFalse(updated.preferences.autoImportOnConnect)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── ToggleFormatFilter ───────────────────────────────────────────────────

    @Test
    fun `ToggleFormatFilter adds format when not selected`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                awaitItem()
                advanceUntilIdle()
                val initial = awaitItem()
                assertTrue(initial.preferences.formatFilter.isEmpty())

                viewModel.onAction(SettingsAction.ToggleFormatFilter(CameraMediaFormat.Jpeg))
                advanceUntilIdle()

                val updated = awaitItem()
                assertTrue(updated.preferences.formatFilter.contains(CameraMediaFormat.Jpeg))

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `ToggleFormatFilter removes format when already selected`() =
        runTest(testDispatcher) {
            // Arrange: JPEG already selected
            fakeRepo.setFormatFilter(setOf(CameraMediaFormat.Jpeg, CameraMediaFormat.Raf))

            viewModel.uiState.test {
                awaitItem()
                advanceUntilIdle()
                val initial = awaitItem()
                assertTrue(initial.preferences.formatFilter.contains(CameraMediaFormat.Jpeg))

                viewModel.onAction(SettingsAction.ToggleFormatFilter(CameraMediaFormat.Jpeg))
                advanceUntilIdle()

                val updated = awaitItem()
                assertFalse(updated.preferences.formatFilter.contains(CameraMediaFormat.Jpeg))
                assertTrue(updated.preferences.formatFilter.contains(CameraMediaFormat.Raf))

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `ToggleFormatFilter can add all formats independently`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                awaitItem()
                advanceUntilIdle()
                awaitItem() // initial loaded

                for (format in CameraMediaFormat.entries) {
                    viewModel.onAction(SettingsAction.ToggleFormatFilter(format))
                    advanceUntilIdle()
                    awaitItem()
                }

                val final = viewModel.uiState.value
                assertEquals(CameraMediaFormat.entries.toSet(), final.preferences.formatFilter)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── SetImportPathTemplate ────────────────────────────────────────────────

    @Test
    fun `SetImportPathTemplate updates path in repository`() =
        runTest(testDispatcher) {
            val newTemplate = "DCIM/Frameport/Photos/{date}"

            viewModel.uiState.test {
                awaitItem()
                advanceUntilIdle()
                awaitItem()

                viewModel.onAction(SettingsAction.SetImportPathTemplate(newTemplate))
                advanceUntilIdle()

                val updated = awaitItem()
                assertEquals(newTemplate, updated.preferences.importPathTemplate)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `SetImportPathTemplate allows empty string`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                awaitItem()
                advanceUntilIdle()
                awaitItem()

                viewModel.onAction(SettingsAction.SetImportPathTemplate(""))
                advanceUntilIdle()

                val updated = awaitItem()
                assertEquals("", updated.preferences.importPathTemplate)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Refresh ─────────────────────────────────────────────────────────────

    @Test
    fun `Refresh action does not change state (DataStore re-emits automatically)`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                awaitItem()
                advanceUntilIdle()
                val before = awaitItem()

                viewModel.onAction(SettingsAction.Refresh)
                advanceUntilIdle()

                // No additional item should be emitted — Refresh is a no-op.
                expectNoEvents()

                assertEquals(before.preferences, viewModel.uiState.value.preferences)
                cancelAndIgnoreRemainingEvents()
            }
        }
}

// ─── Fake ─────────────────────────────────────────────────────────────────────

/**
 * In-memory [SettingsRepository] for unit tests.
 *
 * [_preferences] is a [MutableStateFlow] so collectors receive updates synchronously
 * on the test dispatcher without any real I/O.
 */
private class FakeSettingsRepository : SettingsRepository {
    private val _preferences = MutableStateFlow(ImportPreferences())

    override val preferences: Flow<ImportPreferences> = _preferences.asStateFlow()

    override suspend fun update(prefs: ImportPreferences) {
        _preferences.value = prefs
    }

    override suspend fun setAutoImportOnConnect(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(autoImportOnConnect = enabled)
    }

    override suspend fun setFormatFilter(formats: Set<CameraMediaFormat>) {
        _preferences.value = _preferences.value.copy(formatFilter = formats)
    }

    override suspend fun setImportPathTemplate(template: String) {
        _preferences.value = _preferences.value.copy(importPathTemplate = template)
    }
}
