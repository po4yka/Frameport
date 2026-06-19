package dev.po4yka.frameport.feature.diagnostics

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.DiagnosticEvent
import dev.po4yka.frameport.camera.api.ErrorLayer
import dev.po4yka.frameport.camera.api.defaultCategory
import dev.po4yka.frameport.camera.diagnostics.DiagnosticBundleExporter
import dev.po4yka.frameport.camera.diagnostics.DiagnosticTimeline
import dev.po4yka.frameport.core.model.FrameportError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Unit tests for [DiagnosticsViewModel].
 *
 * Tests instantiate the real [DiagnosticsViewModel] using:
 *  - [DiagnosticTimeline] directly (no Android deps — pure JVM class).
 *  - [DiagnosticBundleExporter] mocked via MockK (requires Android Context).
 *  - [StandardTestDispatcher] for both [IoDispatcher] and Dispatchers.Main.
 *
 * PRIVACY: all synthetic event data uses placeholder values — no real device
 * identifiers, serials, MACs, IPs, BLE addresses, or file paths containing
 * user-identifiable data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope
    private lateinit var timeline: DiagnosticTimeline
    private lateinit var exporter: DiagnosticBundleExporter

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        Dispatchers.setMain(testDispatcher)
        timeline = DiagnosticTimeline()
        exporter = mockk()
        // Default: export succeeds with a synthetic filename.
        coEvery { exporter.export(any()) } returns Result.success(File("frameport-diagnostics-test.zip"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun buildVm(): DiagnosticsViewModel =
        DiagnosticsViewModel(
            timeline = timeline,
            exporter = exporter,
            ioDispatcher = testDispatcher,
        )

    /**
     * SYNTHETIC event factory — no real device identifiers, serials, MACs, IPs, or filenames.
     */
    private fun syntheticEvent(
        layer: ErrorLayer,
        message: String,
        sessionId: String = "session-test-001",
    ): DiagnosticEvent =
        DiagnosticEvent(
            timestamp = Instant.parse("2026-06-14T10:00:00Z"),
            layer = layer,
            category = defaultCategory(layer),
            message = message,
            sessionId = sessionId,
        )

    // ─── events StateFlow ─────────────────────────────────────────────────────

    @Test
    fun `events is empty when timeline has no events`() =
        testScope.runTest {
            val vm = buildVm()
            assertTrue(vm.events.value.isEmpty())
        }

    @Test
    fun `events reflects pre-existing events in timeline`() =
        testScope.runTest {
            timeline.append(syntheticEvent(ErrorLayer.Wifi, "Wi-Fi network requested"))
            val vm = buildVm()
            val job = launch { vm.events.collect { } }
            advanceUntilIdle()
            val snapshot = vm.events.value
            assertEquals(1, snapshot.size)
            assertEquals("Wi-Fi network requested", snapshot[0].message)
            job.cancel()
        }

    @Test
    fun `events updates when timeline appends a new event`() =
        testScope.runTest {
            val vm = buildVm()
            val job = launch { vm.events.collect { } }
            advanceUntilIdle()
            assertTrue(vm.events.value.isEmpty())

            // DiagnosticTimeline.append uses MutableStateFlow.update — synchronous and thread-safe.
            timeline.append(syntheticEvent(ErrorLayer.Protocol, "PTP-IP handshake"))
            advanceUntilIdle()

            assertEquals(1, vm.events.value.size)
            assertEquals("PTP-IP handshake", vm.events.value[0].message)
            job.cancel()
        }

    @Test
    fun `events preserves insertion order across multiple layers`() =
        testScope.runTest {
            val vm = buildVm()
            val job = launch { vm.events.collect { } }
            advanceUntilIdle()

            timeline.append(syntheticEvent(ErrorLayer.Bluetooth, "BLE scan started"))
            timeline.append(syntheticEvent(ErrorLayer.Permission, "Permission granted"))
            timeline.append(syntheticEvent(ErrorLayer.Storage, "Import stored"))
            advanceUntilIdle()

            val snapshot = vm.events.value
            assertEquals(3, snapshot.size)
            assertEquals(ErrorLayer.Bluetooth, snapshot[0].layer)
            assertEquals(ErrorLayer.Permission, snapshot[1].layer)
            assertEquals(ErrorLayer.Storage, snapshot[2].layer)
            job.cancel()
        }

    // ─── exportBundle — success ───────────────────────────────────────────────

    @Test
    fun `exportBundle emits Success carrying file name`() =
        testScope.runTest {
            val vm = buildVm()

            vm.exportResult.test {
                vm.exportBundle()
                testDispatcher.scheduler.advanceUntilIdle()

                val result = awaitItem()
                assertTrue(result is ExportResult.Success)
                assertEquals(
                    "frameport-diagnostics-test.zip",
                    (result as ExportResult.Success).fileName,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `exportBundle fileName is base file name only never a path`() =
        testScope.runTest {
            // Privacy invariant: the UI must show the file NAME only, never a full filesystem path.
            coEvery { exporter.export(any()) } returns
                Result.success(
                    File("/data/user/0/dev.po4yka.frameport/cache/frameport-diagnostics-2026-06-14T10-00-00Z.zip"),
                )
            val vm = buildVm()

            vm.exportResult.test {
                vm.exportBundle()
                testDispatcher.scheduler.advanceUntilIdle()

                val result = awaitItem()
                assertTrue(result is ExportResult.Success)
                val success = result as ExportResult.Success
                assertFalse(
                    "Expected base name only, got full path: ${success.fileName}",
                    '/' in success.fileName,
                )
                assertEquals(
                    "frameport-diagnostics-2026-06-14T10-00-00Z.zip",
                    success.fileName,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `exportBundle calls exporter once per invocation`() =
        testScope.runTest {
            val vm = buildVm()

            vm.exportResult.test {
                vm.exportBundle()
                testDispatcher.scheduler.advanceUntilIdle()
                awaitItem() // first result

                vm.exportBundle()
                testDispatcher.scheduler.advanceUntilIdle()
                awaitItem() // second result

                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 2) { exporter.export(any()) }
        }

    // ─── exportBundle — failure ───────────────────────────────────────────────

    @Test
    fun `exportBundle emits Failure with typed error when exporter throws`() =
        testScope.runTest {
            coEvery { exporter.export(any()) } returns Result.failure(RuntimeException("Disk full"))
            val vm = buildVm()

            vm.exportResult.test {
                vm.exportBundle()
                testDispatcher.scheduler.advanceUntilIdle()

                val result = awaitItem()
                assertTrue(result is ExportResult.Failure)
                assertEquals("Disk full", (result as ExportResult.Failure).error.message)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `exportBundle failure error message is plain text without raw exception type`() =
        testScope.runTest {
            // Privacy + UX invariant: error message must be human-readable,
            // never a raw Java exception class name or stack trace fragment.
            coEvery { exporter.export(any()) } returns Result.failure(RuntimeException("Storage unavailable"))
            val vm = buildVm()

            vm.exportResult.test {
                vm.exportBundle()
                testDispatcher.scheduler.advanceUntilIdle()

                val result = awaitItem()
                assertTrue(result is ExportResult.Failure)
                val failure = result as ExportResult.Failure
                assertFalse(
                    "Error message must be human-readable, got: ${failure.error.message}",
                    "Exception" in failure.error.message,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── ExportResult sealed type ─────────────────────────────────────────────

    @Test
    fun `ExportResult subtypes are correctly identified at runtime`() {
        val success: ExportResult = ExportResult.Success(fileName = "bundle.zip")
        val failure: ExportResult = ExportResult.Failure(FrameportError.Unknown("oops"))

        assertTrue(success is ExportResult.Success)
        assertTrue(failure is ExportResult.Failure)
        assertEquals("bundle.zip", (success as ExportResult.Success).fileName)
        assertEquals("oops", (failure as ExportResult.Failure).error.message)
    }

    // ─── 3-event integration ─────────────────────────────────────────────────

    @Test
    fun `events emits list of 3 after recording 3 events`() =
        testScope.runTest {
            val vm = buildVm()
            val job = launch { vm.events.collect { } }
            advanceUntilIdle()

            timeline.append(syntheticEvent(ErrorLayer.Wifi, "session start"))
            timeline.append(syntheticEvent(ErrorLayer.Protocol, "handshake ok"))
            timeline.append(syntheticEvent(ErrorLayer.MediaTransfer, "transfer complete"))
            advanceUntilIdle()

            val snapshot = vm.events.value
            assertEquals(3, snapshot.size)
            assertNotNull(snapshot[0])
            assertNotNull(snapshot[1])
            assertNotNull(snapshot[2])
            job.cancel()
        }

    // ─── M-10 regression: direct MutableSharedFlow emission ──────────────────

    /**
     * Regression test for M-10 (Channel + bridge-coroutine event-loss bug).
     *
     * Contract: [DiagnosticsViewModel.exportResult] is a [MutableSharedFlow] with
     * replay=0 and extraBufferCapacity=64 emitted to directly from [exportBundle].
     * There is no bridge coroutine that could interpose scheduling between the
     * Channel send and the SharedFlow emit.
     *
     * This test verifies that a Turbine collector subscribed BEFORE [exportBundle]
     * is called receives the item after [advanceUntilIdle], which confirms the
     * direct-emit path works end-to-end on the real production ViewModel.
     *
     * replay=0 means a collector subscribed AFTER the emit misses the event —
     * that is the documented and intentional contract (see KDoc on exportResult).
     * The UI uses repeatOnLifecycle(STARTED) which subscribes while the screen
     * is visible, so events emitted off-screen are intentionally dropped.
     */
    @Test
    fun `exportResult event is delivered to collector subscribed before exportBundle`() =
        testScope.runTest {
            val vm = buildVm()

            // Collector subscribes first — before exportBundle() fires.
            vm.exportResult.test {
                vm.exportBundle()
                testDispatcher.scheduler.advanceUntilIdle()

                // If the old Channel+bridge race were present under StandardTestDispatcher,
                // the bridge coroutine might not have run yet when the collector checks,
                // and the item would appear to be lost. With direct emit this always delivers.
                val result = awaitItem()
                assertTrue(
                    "Expected Success but got $result",
                    result is ExportResult.Success,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Verifies the replay=0 contract: a collector subscribed AFTER the emit
     * does NOT receive the stale event. This prevents confusing stale snackbars
     * when the screen re-enters the STARTED lifecycle state.
     */
    @Test
    fun `exportResult event is NOT replayed to collector subscribed after exportBundle completes`() =
        testScope.runTest {
            val vm = buildVm()

            // Fire and drain before any collector subscribes.
            vm.exportBundle()
            testDispatcher.scheduler.advanceUntilIdle()

            // Collector subscribes after the emit — must not receive the stale event.
            vm.exportResult.test {
                // No item should arrive; expectNoEvents confirms the buffer is empty.
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
}
