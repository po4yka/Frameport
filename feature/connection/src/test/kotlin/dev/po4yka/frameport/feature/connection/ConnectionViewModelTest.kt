package dev.po4yka.frameport.feature.connection

import dev.po4yka.frameport.camera.api.CameraConnectionManager
import dev.po4yka.frameport.camera.api.CameraConnectionState
import dev.po4yka.frameport.camera.api.CameraSession
import dev.po4yka.frameport.core.model.CameraId
import dev.po4yka.frameport.core.model.ConnectionStatus
import dev.po4yka.frameport.core.model.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun startScanAdvancesToFakeCameraFound() = runTest(dispatcher) {
        val viewModel = ConnectionViewModel(FakeCameraConnectionManager())

        viewModel.onAction(ConnectionAction.StartScan)
        advanceUntilIdle()

        assertEquals(ConnectionStep.CameraFound, viewModel.uiState.value.step)
        assertEquals(true, viewModel.uiState.value.canCancel)
    }
}

private class FakeCameraConnectionManager : CameraConnectionManager {
    override val state = MutableStateFlow(
        CameraConnectionState(
            status = ConnectionStatus.Disconnected,
            activeSession = null,
            lastError = null,
        ),
    )

    override suspend fun connect(cameraId: CameraId, transportKind: TransportKind): Result<CameraSession> =
        Result.failure(IllegalStateException("Fake manager does not connect."))

    override suspend fun disconnect() {
        state.value = state.value.copy(status = ConnectionStatus.Disconnected)
    }
}
