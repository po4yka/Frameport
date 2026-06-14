package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.TransferId
import dev.po4yka.frameport.camera.api.TransferProgress
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.storage.session.SessionProgressStore
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM unit tests for [CameraSessionController].
 *
 * Tests:
 * - T1: onTerminal fires when state reaches Closed (start/stop symmetry).
 * - T2: onTerminal fires when state reaches Failed (error-driven stop).
 * - Non-terminal states (Idle, Connecting, SessionReady) do NOT fire onTerminal.
 * - Progress upsert called exactly once per emission (G6).
 * - Controller.stop() cancels collection so a later terminal state is not observed.
 *
 * Pattern: the controller's collect coroutines are launched into a CoroutineScope backed
 * by [UnconfinedTestDispatcher], so they run eagerly inline and do not require manual
 * scheduler draining for each state update. The scope is cancelled explicitly at test end
 * via [kotlinx.coroutines.cancel] (via controller.stop() or test teardown).
 *
 * No Robolectric, no Android framework.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraSessionControllerTest {
    /** Creates a CoroutineScope backed by UnconfinedTestDispatcher for eager collect execution. */
    private fun testControllerScope() = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    // ── T1: onTerminal fires when state reaches Closed ────────────────────────

    @Test
    fun `given session reaches Closed, when controller running, then onTerminal is called`() =
        runTest {
            val stateFlow = MutableStateFlow<CameraSessionState>(CameraSessionState.Idle)
            val progressFlow = MutableSharedFlow<TransferProgress>()
            val store = mockk<SessionProgressStore>(relaxed = true)
            var terminalCount = 0

            val scope = testControllerScope()
            val controller =
                CameraSessionController(
                    sessionId = 7L,
                    sessionState = stateFlow,
                    progressFlow = progressFlow,
                    sessionProgressStore = store,
                    onTerminal = { terminalCount++ },
                )
            controller.start(scope)

            // Unconfined dispatcher runs collectors inline; state changes are observed immediately.
            stateFlow.value = CameraSessionState.Connecting
            stateFlow.value = CameraSessionState.SessionReady(SessionId(1L))
            stateFlow.value = CameraSessionState.Closed

            controller.stop()

            assertEquals(1, terminalCount)
        }

    // ── T2: onTerminal fires on Failed (error-driven stop) ────────────────────

    @Test
    fun `given session reaches Failed, when controller running, then onTerminal is called`() =
        runTest {
            val stateFlow = MutableStateFlow<CameraSessionState>(CameraSessionState.Connecting)
            val progressFlow = MutableSharedFlow<TransferProgress>()
            val store = mockk<SessionProgressStore>(relaxed = true)
            var terminalCount = 0

            val scope = testControllerScope()
            val controller =
                CameraSessionController(
                    sessionId = 7L,
                    sessionState = stateFlow,
                    progressFlow = progressFlow,
                    sessionProgressStore = store,
                    onTerminal = { terminalCount++ },
                )
            controller.start(scope)

            stateFlow.value = CameraSessionState.Failed(FrameportError.Unknown("handshake rejected"))

            controller.stop()

            assertEquals(1, terminalCount)
        }

    // ── Non-terminal states do NOT fire onTerminal ────────────────────────────

    @Test
    fun `given non-terminal states only, when controller running, then onTerminal is never called`() =
        runTest {
            val stateFlow = MutableStateFlow<CameraSessionState>(CameraSessionState.Idle)
            val progressFlow = MutableSharedFlow<TransferProgress>()
            val store = mockk<SessionProgressStore>(relaxed = true)
            var terminalCount = 0

            val scope = testControllerScope()
            val controller =
                CameraSessionController(
                    sessionId = 7L,
                    sessionState = stateFlow,
                    progressFlow = progressFlow,
                    sessionProgressStore = store,
                    onTerminal = { terminalCount++ },
                )
            controller.start(scope)

            stateFlow.value = CameraSessionState.Connecting
            stateFlow.value = CameraSessionState.SessionReady(SessionId(99L))

            controller.stop()

            assertEquals(0, terminalCount)
        }

    // ── Progress upsert called N times for N emissions ────────────────────────

    @Test
    fun `given N progress emissions, when controller running, then upsert called N times`() =
        runTest {
            val stateFlow =
                MutableStateFlow<CameraSessionState>(
                    CameraSessionState.SessionReady(SessionId(1L)),
                )
            // extraBufferCapacity=10 so tryEmit succeeds without a suspended collector.
            val progressFlow = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 10)
            val store = mockk<SessionProgressStore>(relaxed = true)

            val scope = testControllerScope()
            val controller =
                CameraSessionController(
                    sessionId = 7L,
                    sessionState = stateFlow,
                    progressFlow = progressFlow,
                    sessionProgressStore = store,
                    onTerminal = {},
                )
            controller.start(scope)

            val transferId = TransferId(100L)
            progressFlow.emit(TransferProgress(transferId, bytesTransferred = 1024L, totalBytes = 4096L))
            progressFlow.emit(TransferProgress(transferId, bytesTransferred = 2048L, totalBytes = 4096L))
            progressFlow.emit(TransferProgress(transferId, bytesTransferred = 4096L, totalBytes = 4096L))

            controller.stop()

            coVerify(exactly = 3) {
                store.upsert(
                    sessionId = any(),
                    objectHandle = any(),
                    bytesTransferred = any(),
                    totalBytes = any(),
                    updatedAtMillis = any(),
                )
            }
        }

    // ── Stop cancels collection — terminal state after stop is NOT observed ───

    @Test
    fun `given controller stopped before terminal state, when Closed emitted, then onTerminal is not called`() =
        runTest {
            val stateFlow = MutableStateFlow<CameraSessionState>(CameraSessionState.Connecting)
            val progressFlow = MutableSharedFlow<TransferProgress>()
            val store = mockk<SessionProgressStore>(relaxed = true)
            var terminalCount = 0

            val scope = testControllerScope()
            val controller =
                CameraSessionController(
                    sessionId = 7L,
                    sessionState = stateFlow,
                    progressFlow = progressFlow,
                    sessionProgressStore = store,
                    onTerminal = { terminalCount++ },
                )
            controller.start(scope)

            // Stop before terminal state arrives
            controller.stop()

            stateFlow.value = CameraSessionState.Closed
            advanceUntilIdle()

            assertEquals(0, terminalCount)
        }

    // ── Terminal marks the progress row completed, exactly once ───────────────

    @Test
    fun `given multiple terminal states, when controller running, then onTerminal and markCompleted fire once`() =
        runTest {
            val stateFlow = MutableStateFlow<CameraSessionState>(CameraSessionState.Connecting)
            val progressFlow = MutableSharedFlow<TransferProgress>()
            val store = mockk<SessionProgressStore>(relaxed = true)
            var terminalCount = 0

            val scope = testControllerScope()
            val controller =
                CameraSessionController(
                    sessionId = 7L,
                    sessionState = stateFlow,
                    progressFlow = progressFlow,
                    sessionProgressStore = store,
                    onTerminal = { terminalCount++ },
                )
            controller.start(scope)

            // Two distinct terminal transitions — must be handled exactly once.
            stateFlow.value = CameraSessionState.Failed(FrameportError.Unknown("io error"))
            stateFlow.value = CameraSessionState.Closed

            controller.stop()

            assertEquals(1, terminalCount)
            // The managed session's row is marked completed (not left "in_progress"), so the
            // next launch does not misreport it as an interrupted transfer.
            coVerify(exactly = 1) { store.markCompleted(sessionId = 7L, updatedAtMillis = any()) }
        }
}
