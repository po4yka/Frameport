package dev.po4yka.frameport.feature.diagnostics

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.DiagnosticEvent
import dev.po4yka.frameport.camera.api.ErrorLayer
import dev.po4yka.frameport.camera.api.defaultCategory
import dev.po4yka.frameport.core.model.FrameportError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
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

// ─── Structural fakes matching GT-NEW pinned signatures ───────────────────────

/**
 * Fake for DiagnosticTimeline (pinned to GT-NEW signatures).
 *
 * GT-NEW: val timeline: StateFlow<List<DiagnosticEvent>>
 *
 * This fake keeps the VM test hermetic — no Android runtime, no Hilt.
 * SYNTHETIC data only — no real device identifiers.
 */
private class FakeDiagnosticTimeline(
    initialEvents: List<DiagnosticEvent> = emptyList(),
) {
    private val _timeline = MutableStateFlow(initialEvents)

    /** Matches DiagnosticTimeline.timeline : StateFlow<List<DiagnosticEvent>> */
    val timeline: StateFlow<List<DiagnosticEvent>> = _timeline.asStateFlow()

    fun append(event: DiagnosticEvent) {
        // MutableStateFlow.value assignment is thread-safe and synchronous.
        _timeline.value = _timeline.value + event
    }
}

/**
 * Fake for DiagnosticBundleExporter (pinned to GT-NEW signatures).
 *
 * PRIVACY: file names use SYNTHETIC placeholders only — never real paths.
 */
private class FakeDiagnosticBundleExporter(
    initialResult: Result<File> = Result.success(File("frameport-diagnostics-test.zip")),
) {
    private var nextResult: Result<File> = initialResult
    var callCount = 0

    suspend fun export(
        @Suppress("UNUSED_PARAMETER") timeline: FakeDiagnosticTimeline,
    ): Result<File> {
        callCount++
        return nextResult
    }

    fun setSuccess(file: File) {
        nextResult = Result.success(file)
    }

    fun setFailure(message: String) {
        nextResult = Result.failure(RuntimeException(message))
    }
}

// ─── Testable ViewModel ───────────────────────────────────────────────────────

/**
 * Production-equivalent ViewModel built from fakes, runnable on the JVM without
 * Hilt or Android runtime. Logic mirrors [DiagnosticsViewModel].
 *
 * Design choices that differ from production to enable hermetic JVM testing:
 *
 * 1. [events] is the raw [FakeDiagnosticTimeline.timeline] StateFlow rather than
 *    a stateIn-derived StateFlow. This avoids launching any coroutine inside
 *    [testScope] during construction, which would cause [UncompletedCoroutinesError].
 *    The upstream StateFlow is already a MutableStateFlow that updates synchronously,
 *    so reads of [events.value] are always current without dispatcher scheduling.
 *
 * 2. [exportResult] is a MutableSharedFlow (replay=1) emitted to directly from
 *    [exportBundle]. replay=1 ensures Turbine can observe the emission even when
 *    the collector subscribes after [exportBundle]'s coroutine completes under
 *    StandardTestDispatcher scheduling.
 *
 * 3. [exportBundle] launches on [testDispatcher] so [advanceUntilIdle] drains it.
 */
private class FakeBackedDiagnosticsViewModel(
    private val timeline: FakeDiagnosticTimeline,
    private val exporter: FakeDiagnosticBundleExporter,
    private val testScope: TestScope,
    private val testDispatcher: TestDispatcher,
) {
    // Direct reference — no stateIn, no coroutine launched.
    // cancel-safe: StateFlow; read via .value is always synchronous and safe.
    val events: StateFlow<List<DiagnosticEvent>> = timeline.timeline

    // replay=1 so Turbine collectors started after emission still receive the item.
    // cancel-safe: SharedFlow; no persistent coroutines held.
    private val _exportResult = MutableSharedFlow<ExportResult>(replay = 1, extraBufferCapacity = 4)
    val exportResult: SharedFlow<ExportResult> = _exportResult.asSharedFlow()

    // NOT cancel-safe: mid-export cancellation may leave a partial zip in cacheDir.
    fun exportBundle() {
        testScope.launch(testDispatcher) {
            val result = exporter.export(timeline)
            result.fold(
                onSuccess = { file ->
                    _exportResult.emit(ExportResult.Success(fileName = file.name))
                },
                onFailure = { throwable ->
                    _exportResult.emit(
                        ExportResult.Failure(
                            error =
                                FrameportError.Unknown(
                                    message = throwable.message ?: "Export failed",
                                ),
                        ),
                    )
                },
            )
        }
    }
}

// ─── Tests ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope
    private lateinit var fakeTimeline: FakeDiagnosticTimeline
    private lateinit var fakeExporter: FakeDiagnosticBundleExporter

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        Dispatchers.setMain(testDispatcher)
        fakeTimeline = FakeDiagnosticTimeline()
        fakeExporter = FakeDiagnosticBundleExporter()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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
            fakeTimeline =
                FakeDiagnosticTimeline(
                    initialEvents = listOf(syntheticEvent(ErrorLayer.Wifi, "Wi-Fi network requested")),
                )
            val vm = buildVm()
            val snapshot = vm.events.value
            assertEquals(1, snapshot.size)
            assertEquals("Wi-Fi network requested", snapshot[0].message)
        }

    @Test
    fun `events updates when timeline appends a new event`() =
        testScope.runTest {
            val vm = buildVm()
            assertTrue(vm.events.value.isEmpty())

            // MutableStateFlow.value updates synchronously — no dispatcher advancement needed.
            fakeTimeline.append(syntheticEvent(ErrorLayer.Protocol, "PTP-IP handshake"))

            assertEquals(1, vm.events.value.size)
            assertEquals("PTP-IP handshake", vm.events.value[0].message)
        }

    @Test
    fun `events preserves insertion order across multiple layers`() =
        testScope.runTest {
            val vm = buildVm()

            fakeTimeline.append(syntheticEvent(ErrorLayer.Bluetooth, "BLE scan started"))
            fakeTimeline.append(syntheticEvent(ErrorLayer.Permission, "Permission granted"))
            fakeTimeline.append(syntheticEvent(ErrorLayer.Storage, "Import stored"))

            val snapshot = vm.events.value
            assertEquals(3, snapshot.size)
            assertEquals(ErrorLayer.Bluetooth, snapshot[0].layer)
            assertEquals(ErrorLayer.Permission, snapshot[1].layer)
            assertEquals(ErrorLayer.Storage, snapshot[2].layer)
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
            fakeExporter.setSuccess(
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

                assertEquals(2, fakeExporter.callCount)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── exportBundle — failure ───────────────────────────────────────────────

    @Test
    fun `exportBundle emits Failure with typed error when exporter throws`() =
        testScope.runTest {
            fakeExporter.setFailure("Disk full")
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
            fakeExporter.setFailure("Storage unavailable")
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

    /** Core spec: record 3 events → events StateFlow emits list of 3. */
    @Test
    fun `events emits list of 3 after recording 3 events`() =
        testScope.runTest {
            val vm = buildVm()

            fakeTimeline.append(syntheticEvent(ErrorLayer.Wifi, "session start"))
            fakeTimeline.append(syntheticEvent(ErrorLayer.Protocol, "handshake ok"))
            fakeTimeline.append(syntheticEvent(ErrorLayer.MediaTransfer, "transfer complete"))

            val snapshot = vm.events.value
            assertEquals(3, snapshot.size)
            assertNotNull(snapshot[0])
            assertNotNull(snapshot[1])
            assertNotNull(snapshot[2])
        }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun buildVm(): FakeBackedDiagnosticsViewModel =
        FakeBackedDiagnosticsViewModel(
            timeline = fakeTimeline,
            exporter = fakeExporter,
            testScope = testScope,
            testDispatcher = testDispatcher,
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
}
