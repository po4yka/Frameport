package dev.po4yka.frameport.feature.connection

import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.model.FrameportError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startScanAdvancesToFakeCameraFound() =
        runTest(dispatcher) {
            val viewModel = ConnectionViewModel(FakeCameraRepository())

            viewModel.onAction(ConnectionAction.StartScan)
            advanceUntilIdle()

            assertEquals(ConnectionStep.CameraFound, viewModel.uiState.value.step)
            assertEquals(true, viewModel.uiState.value.canCancel)
        }

    @Test
    fun cancelWithIdleSessionResetsToDefaultState() =
        runTest(dispatcher) {
            val fakeRepo = FakeCameraRepository()
            val viewModel = ConnectionViewModel(fakeRepo)

            viewModel.onAction(ConnectionAction.StartScan)
            advanceUntilIdle()
            viewModel.onAction(ConnectionAction.Cancel)
            advanceUntilIdle()

            assertEquals(ConnectionStep.Idle, viewModel.uiState.value.step)
        }

    @Test
    fun cancelWithActiveSessionClosesSession() =
        runTest(dispatcher) {
            val fakeRepo = FakeCameraRepository()
            val sessionId = SessionId(99L)
            fakeRepo.setSessionReady(sessionId)
            val viewModel = ConnectionViewModel(fakeRepo)

            viewModel.onAction(ConnectionAction.Cancel)
            advanceUntilIdle()

            assertEquals(true, fakeRepo.closedSessions.contains(sessionId))
            assertEquals(ConnectionStep.Idle, viewModel.uiState.value.step)
        }
}

/** Inline fake [CameraRepository] for connection tests — avoids importing :core:testing. */
private class FakeCameraRepository : CameraRepository {
    private val _sessionState = MutableStateFlow<CameraSessionState>(CameraSessionState.Idle)
    override val sessionState: StateFlow<CameraSessionState> = _sessionState.asStateFlow()

    val closedSessions = mutableListOf<SessionId>()

    fun setSessionReady(sessionId: SessionId) {
        _sessionState.value = CameraSessionState.SessionReady(sessionId)
    }

    // cancel-safe: no real suspension; transitions state immediately.
    override suspend fun openSession(credentials: CameraWifiCredentials): Result<SessionId> {
        val id = SessionId(1L)
        _sessionState.value = CameraSessionState.SessionReady(id)
        return Result.success(id)
    }

    // cancel-safe: records closed session.
    override suspend fun closeSession(sessionId: SessionId) {
        closedSessions.add(sessionId)
        _sessionState.value = CameraSessionState.Closed
    }
}
