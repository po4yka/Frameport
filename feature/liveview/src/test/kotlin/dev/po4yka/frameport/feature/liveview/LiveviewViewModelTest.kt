package dev.po4yka.frameport.feature.liveview

import android.graphics.Bitmap
import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.LiveViewRepository
import dev.po4yka.frameport.camera.api.LiveViewUiState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.domain.LiveViewUseCase
import dev.po4yka.frameport.core.testing.FakeDiagnosticsRepository
import dev.po4yka.frameport.core.testing.FakeFujiNativeSdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [LiveviewViewModel].
 *
 * Strategy: Arrange-Act-Assert, named in Given-When-Then style per the team convention.
 *
 * Dispatcher discipline: [StandardTestDispatcher] with MANUAL time control.
 * - [testScheduler.runCurrent()] — processes only tasks scheduled at the CURRENT virtual time.
 *   Used after frame delivery or stop() to avoid looping on the fps-tick's `delay(1000L)`.
 * - [advanceTimeBy(N)] — advances the virtual clock by N ms without looping indefinitely.
 *   Used exactly ONCE in test 4 to fire the fps tick.
 * - [advanceUntilIdle()] is intentionally NOT used once the fps tick coroutine is running
 *   (it would loop: delay 1 s → tick → delay 1 s → tick → … → OOM).
 *
 * Decode seam: [LiveviewViewModel.frameDecoder] is an internal `var` overridden after
 * construction. The ViewModel enters Streaming on the first frame *arrival* (not decode
 * success), so Streaming is reachable even when the decoder returns null. No Robolectric needed.
 *
 * [FakeLiveViewSource]: `callbackFlow`-backed source with proper cancellation semantics.
 * [isClosed] is set in `awaitClose` when the downstream collector is cancelled — proves
 * that [stop] terminates the upstream (and would stop the Rust read loop in production).
 *
 * Test matrix (all eight required by M16 task spec):
 * 1. Initial uiState is [LiveViewUiState.Idle].
 * 2. stop() when Idle → [LiveViewUiState.Stopped].
 * 3. start() + frame → Connecting → Streaming.
 * 4. 10 frames → fps estimate within ±5 after 1-second tick.
 * 5. stop() → Stopped + [FakeLiveViewSource.isClosed] == true.
 * 6. Source throws on frame 5 → [LiveViewUiState.Error], no exception propagation.
 * 7. Frame arrives → [frameDecoder] invoked; decode path exercised.
 * 8. stop() → [FakeDiagnosticsRepository] records "live-view stopped".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveviewViewModelTest {
    // ─── Infrastructure ───────────────────────────────────────────────────────

    private val testDispatcher = StandardTestDispatcher()

    /**
     * Counts decode-seam invocations. Returns null (JVM bitmap codec absent).
     * Frame *arrival* (not decode success) drives the state machine, so Streaming is
     * reachable from JVM unit tests with a null-returning decoder.
     */
    private val decoderCallCount = AtomicInteger(0)
    private val nullDecoder: (ByteArray) -> Bitmap? = { _ ->
        decoderCallCount.incrementAndGet()
        null
    }

    private lateinit var fakeSdk: FakeFujiNativeSdk
    private lateinit var fakeDiagnostics: FakeDiagnosticsRepository
    private lateinit var useCase: LiveViewUseCase
    private lateinit var viewModel: LiveviewViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        decoderCallCount.set(0)
        fakeSdk = FakeFujiNativeSdk()
        fakeDiagnostics = FakeDiagnosticsRepository()
        useCase = LiveViewUseCase(FakeSdkLiveViewRepository(fakeSdk))
        viewModel = buildViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Builds a [LiveviewViewModel] with [decoder] wired into the internal decode seam.
     * [frameDecoder] is NOT a constructor parameter — Hilt KSP never sees it.
     */
    private fun buildViewModel(
        liveViewUseCase: LiveViewUseCase = useCase,
        decoder: (ByteArray) -> Bitmap? = nullDecoder,
    ): LiveviewViewModel =
        LiveviewViewModel(
            liveViewUseCase = liveViewUseCase,
            diagnosticsRepository = fakeDiagnostics,
            ioDispatcher = testDispatcher,
        ).also { it.frameDecoder = decoder }

    // ─── Test 1: Initial state ────────────────────────────────────────────────

    @Test
    fun `given fresh ViewModel, when no action taken, then uiState is Idle`() =
        runTest {
            assertEquals(LiveViewUiState.Idle, viewModel.uiState.first())
        }

    // ─── Test 2: stop() when Idle ─────────────────────────────────────────────

    @Test
    fun `given Idle state, when stop called, then uiState transitions to Stopped`() =
        runTest {
            viewModel.uiState.test {
                assertEquals(LiveViewUiState.Idle, awaitItem())
                viewModel.stop()
                assertEquals(LiveViewUiState.Stopped, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Test 3: Connecting → Streaming ──────────────────────────────────────

    @Test
    fun `given start called, when first frame arrives, then state transitions Connecting to Streaming`() =
        runTest {
            val source = FakeLiveViewSource()
            val vm = buildViewModel(liveViewUseCase = source.asUseCase())

            vm.uiState.test {
                assertEquals(LiveViewUiState.Idle, awaitItem())

                vm.start(SessionId(1L), liveViewFd = -1)
                // runCurrent: process the Connecting state assignment; no clock advance
                testScheduler.runCurrent()
                assertEquals(LiveViewUiState.Connecting, awaitItem())

                source.send(MINIMAL_JPEG)
                // runCurrent: deliver the frame and process state change; keeps fps-tick at delay(1000)
                testScheduler.runCurrent()

                val state = awaitItem()
                assertTrue("Expected Streaming after first frame, got $state", state is LiveViewUiState.Streaming)

                cancelAndIgnoreRemainingEvents()
            }
            vm.stop()
            source.close()
        }

    // ─── Test 4: 10 frames at ~30 fps → fps within ±5 ───────────────────────

    /**
     * Given 10 frames emitted through [FakeLiveViewSource], when the fps-tick fires after
     * 1 virtual second, the reported fps is in [5, 15] (target: 10 fps, ±5 tolerance).
     *
     * Clock discipline:
     * - [testScheduler.runCurrent()] delivers each frame without advancing the clock.
     * - [advanceTimeBy(1_001L)] advances the clock exactly once to fire the tick.
     * - A second [testScheduler.runCurrent()] processes the tick result without looping.
     */
    @Test
    fun `given 10 frames at 30fps, when fps tick fires after 1 second, then reported fps is within 5 of 10`() =
        runTest {
            val source = FakeLiveViewSource()
            val vm = buildViewModel(liveViewUseCase = source.asUseCase())

            vm.uiState.test {
                assertEquals(LiveViewUiState.Idle, awaitItem())

                vm.start(SessionId(10L), liveViewFd = -1)
                testScheduler.runCurrent()
                assertEquals(LiveViewUiState.Connecting, awaitItem())

                // Deliver 10 frames without advancing the virtual clock
                repeat(10) { i ->
                    source.send(MINIMAL_JPEG)
                    testScheduler.runCurrent()
                    if (i == 0) {
                        val first = awaitItem()
                        assertTrue("Expected Streaming on frame 0, got $first", first is LiveViewUiState.Streaming)
                    }
                }

                // Advance clock exactly 1 s to fire the fps tick once; runCurrent processes the result
                advanceTimeBy(1_001L)
                testScheduler.runCurrent()

                val afterTick = awaitItem()
                assertTrue("Expected Streaming after tick, got $afterTick", afterTick is LiveViewUiState.Streaming)
                val fps = (afterTick as LiveViewUiState.Streaming).fps
                // 10 frames counted in the 1-second tick window; ±5 tolerance for scheduler jitter
                assertTrue(
                    "Expected fps in [5, 15] (10 frames / 1s window, ±5 tolerance), got $fps",
                    fps >= 5f && fps <= 15f,
                )

                cancelAndIgnoreRemainingEvents()
            }
            vm.stop()
            source.close()
        }

    // ─── Test 5: stop() → Stopped + isClosed ─────────────────────────────────

    /**
     * Given a streaming ViewModel backed by [FakeLiveViewSource], when [stop] is called:
     * - uiState becomes [LiveViewUiState.Stopped].
     * - [FakeLiveViewSource.isClosed] is true: `callbackFlow`'s `awaitClose` ran, proving
     *   the collection coroutine was cancelled (Rust read loop would stop in production).
     */
    @Test
    fun `given streaming, when stop called, then uiState is Stopped and source is closed`() =
        runTest {
            val source = FakeLiveViewSource()
            val vm = buildViewModel(liveViewUseCase = source.asUseCase())

            vm.start(SessionId(20L), liveViewFd = -1)
            testScheduler.runCurrent()

            source.send(MINIMAL_JPEG)
            testScheduler.runCurrent()

            val mid = vm.uiState.first()
            assertTrue("Expected Streaming before stop, got $mid", mid is LiveViewUiState.Streaming)

            // Act — stop cancels streamJob and fpsTickJob
            vm.stop()
            // Drain pending continuations without advancing the clock:
            // Each pass processes one layer of suspension/resumption:
            // 1. CancellationException delivery to streamJob collector
            // 2. callbackFlow producer receives cancellation → awaitClose block runs → isClosed = true
            // 3. Any remaining cleanup continuations
            repeat(5) { testScheduler.runCurrent() }

            assertEquals(LiveViewUiState.Stopped, vm.uiState.first())
            assertTrue("Expected FakeLiveViewSource.isClosed == true after stop()", source.isClosed)
        }

    // ─── Test 6: Source throws on frame 5 → Error ────────────────────────────

    /**
     * Given a source that emits 4 frames then throws [RuntimeException] on the 5th:
     * - ViewModel catch block captures the exception (RuntimeException extends Exception).
     * - uiState → [LiveViewUiState.Error] with non-empty message.
     * - Exception does NOT propagate to the test coroutine.
     *
     * [ThrowingLiveViewSource] uses a cold `flow {}` that terminates after the throw,
     * so no OOM risk from infinite collection.
     */
    @Test
    fun `given source throws on frame 5, when collecting frames, then uiState becomes Error without crashing`() =
        runTest {
            val vm =
                buildViewModel(
                    liveViewUseCase = ThrowingLiveViewSource(goodFrames = 4, MINIMAL_JPEG).asUseCase(),
                )

            vm.uiState.test {
                assertEquals(LiveViewUiState.Idle, awaitItem())

                vm.start(SessionId(30L), liveViewFd = -1)
                testScheduler.runCurrent()
                assertEquals(LiveViewUiState.Connecting, awaitItem())

                // Cold flow emits all 4 frames then throws synchronously; runCurrent processes everything
                testScheduler.runCurrent()
                val streaming = awaitItem()
                assertTrue(
                    "Expected Streaming after first frame, got $streaming",
                    streaming is LiveViewUiState.Streaming,
                )

                // Process remaining frames + throw + catch block
                testScheduler.runCurrent()
                val error = awaitItem()
                assertTrue("Expected Error after throw, got $error", error is LiveViewUiState.Error)
                assertTrue(
                    "Expected non-empty error message",
                    (error as LiveViewUiState.Error).message.isNotEmpty(),
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Test 7: Frame arrives → decode path exercised ───────────────────────

    /**
     * Given a frame emitted via [FakeFujiNativeSdk.emitLiveViewFrame]:
     * - [frameDecoder] is called ≥1 time (decode wiring is correct).
     * - uiState is Streaming (frame arrival, not decode success, drives the state machine).
     * - bitmapState is null (JVM codec absent; non-null verified by instrumented tests).
     */
    @Test
    fun `given a frame arrives, when frameDecoder is invoked, then decode path is exercised`() =
        runTest {
            viewModel.start(SessionId(40L), liveViewFd = -1)
            testScheduler.runCurrent()

            fakeSdk.emitLiveViewFrame(MINIMAL_JPEG)
            testScheduler.runCurrent()

            assertTrue(
                "Expected frameDecoder invoked ≥1 time, got ${decoderCallCount.get()}",
                decoderCallCount.get() >= 1,
            )
            val state = viewModel.uiState.first()
            assertTrue("Expected Streaming after frame arrival, got $state", state is LiveViewUiState.Streaming)

            viewModel.stop()
        }

    // ─── Test 8: Diagnostics on stop ─────────────────────────────────────────

    /**
     * Given streaming is active, when [stop] is called:
     * - The ViewModel's catch block (triggered by CancellationException from job cancellation)
     *   calls [recordStopDiagnostic].
     * - [FakeDiagnosticsRepository.recordedEvents] contains "live-view stopped".
     */
    @Test
    fun `given streaming, when stop called, then diagnostics records live-view stopped event`() =
        runTest {
            viewModel.start(SessionId(5L), liveViewFd = -1)
            testScheduler.runCurrent()

            fakeSdk.emitLiveViewFrame(MINIMAL_JPEG)
            testScheduler.runCurrent()

            assertTrue(viewModel.uiState.first() is LiveViewUiState.Streaming)

            viewModel.stop()
            // Drain all pending continuations without advancing the clock:
            // 1. CancellationException delivery to streamJob
            // 2. catch block: stopFpsTick() + withContext(ioDispatcher) dispatch in recordStopDiagnostic
            // 3. recordEvent call inside withContext
            // Multiple runCurrent() passes are needed because each suspend point queues a new task.
            repeat(5) { testScheduler.runCurrent() }

            assertTrue(
                "Expected 'live-view stopped' diagnostic event. " +
                    "Recorded: ${fakeDiagnostics.recordedEvents.map { it.message }}",
                fakeDiagnostics.recordedEvents.any { it.message == "live-view stopped" },
            )
        }

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        /**
         * Minimal JPEG stub: SOI (0xFF 0xD8) + EOI (0xFF 0xD9).
         * Per master-constants.md: JPEG_SOI = 0xFFD8, JPEG_EOI = 0xFFD9.
         */
        private val MINIMAL_JPEG =
            byteArrayOf(
                0xFF.toByte(),
                0xD8.toByte(),
                0xFF.toByte(),
                0xD9.toByte(),
            )
    }
}

// ─── FakeLiveViewSource ───────────────────────────────────────────────────────

/**
 * Controllable live-view frame source backed by `callbackFlow`.
 *
 * Using `callbackFlow` (not a hot SharedFlow) gives clean cancellation semantics:
 * - [send] delivers a frame to the collector via the channel.
 * - [close] completes the flow normally.
 * - When the downstream collector is cancelled, `awaitClose` runs and sets [isClosed].
 *
 * This avoids the OOM that occurs when `advanceUntilIdle()` tries to drain a coroutine
 * suspended on a never-completing hot SharedFlow after the ViewModel is stopped.
 */
private class FakeLiveViewSource {
    @Volatile
    var isClosed: Boolean = false
        private set

    // Wired in the callbackFlow body; null before the flow is collected.
    private var channelSend: (suspend (ByteArray) -> Unit)? = null
    private var channelClose: (() -> Unit)? = null

    /** Deliver one JPEG frame to the active collector. */
    suspend fun send(jpeg: ByteArray) {
        channelSend?.invoke(jpeg)
    }

    /** Complete the source normally (camera stopped the stream). */
    fun close() {
        channelClose?.invoke()
    }

    fun asUseCase(): LiveViewUseCase = LiveViewUseCase(StubRepository())

    // cancel-safe: callbackFlow with awaitClose; cancellation of the downstream
    // collector triggers awaitClose which sets isClosed = true.
    private inner class StubRepository : LiveViewRepository {
        override fun liveViewFrames(
            sessionId: SessionId,
            liveViewFd: Int,
        ): Flow<ByteArray> =
            callbackFlow {
                channelSend = { jpeg -> send(jpeg) }
                channelClose = { channel.close() }
                awaitClose {
                    isClosed = true
                    channelSend = null
                    channelClose = null
                }
            }
    }
}

// ─── FakeSdkLiveViewRepository ───────────────────────────────────────────────

/**
 * Adapts [FakeFujiNativeSdk] to [LiveViewRepository] so tests that drive frames via
 * [FakeFujiNativeSdk.emitLiveViewFrame] (tests 7 and 8) can construct [LiveViewUseCase].
 */
private class FakeSdkLiveViewRepository(
    private val sdk: FakeFujiNativeSdk,
) : LiveViewRepository {
    override fun liveViewFrames(
        sessionId: SessionId,
        liveViewFd: Int,
    ): Flow<ByteArray> = sdk.liveViewFrames(sessionId, liveViewFd)
}

// ─── ThrowingLiveViewSource ───────────────────────────────────────────────────

/**
 * Live-view source that emits [goodFrames] valid frames then throws [RuntimeException].
 * Cold `flow {}` — terminates after throw, no infinite collection risk.
 */
private class ThrowingLiveViewSource(
    private val goodFrames: Int,
    private val frameBytes: ByteArray,
) {
    fun asUseCase(): LiveViewUseCase = LiveViewUseCase(StubRepository())

    // NOT cancel-safe: emits N frames then throws; partial delivery on cancellation.
    private inner class StubRepository : LiveViewRepository {
        override fun liveViewFrames(
            sessionId: SessionId,
            liveViewFd: Int,
        ): Flow<ByteArray> =
            flow {
                repeat(goodFrames) { emit(frameBytes) }
                throw RuntimeException("Simulated live-view failure at frame ${goodFrames + 1}")
            }
    }
}
