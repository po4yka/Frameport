package dev.po4yka.frameport.camera.domain

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.testing.FakeCameraProfileRepository
import dev.po4yka.frameport.core.testing.FakeCameraRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenCameraSessionUseCaseTest {
    private lateinit var fakeRepository: FakeCameraRepository
    private lateinit var fakeProfileRepository: FakeCameraProfileRepository
    private lateinit var useCase: OpenCameraSessionUseCase
    private val testDispatcher = UnconfinedTestDispatcher()

    private val credentials = CameraWifiCredentials(ssid = "FUJIFILM-XT5", passphrase = null)

    @Before
    fun setUp() {
        fakeRepository = FakeCameraRepository()
        fakeProfileRepository = FakeCameraProfileRepository()
        useCase = OpenCameraSessionUseCase(fakeRepository, fakeProfileRepository, testDispatcher)
    }

    @Test
    fun `given fake repo succeeds, when invoked, then emits Connecting before SessionReady`() =
        runTest(testDispatcher) {
            // Given
            val expectedSessionId = SessionId(99L)
            fakeRepository.simulateSuccess(expectedSessionId)

            // When / Then
            useCase(credentials).test {
                assertEquals(CameraSessionState.Connecting, awaitItem())
                assertEquals(CameraSessionState.SessionReady(expectedSessionId), awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun `given fake repo simulates failure, when invoked, then emits Connecting then Failed with error`() =
        runTest(testDispatcher) {
            // Given
            val expectedError = FrameportError.ProtocolUnavailable("PTP-IP handshake rejected")
            fakeRepository.simulateFailure(expectedError)

            // When / Then
            useCase(credentials).test {
                assertEquals(CameraSessionState.Connecting, awaitItem())
                val failed = awaitItem()
                assertTrue(failed is CameraSessionState.Failed)
                // The use case wraps the throwable message into FrameportError.Unknown;
                // assert the message round-trips through the error path.
                val failedState = failed as CameraSessionState.Failed
                assertEquals(expectedError.message, failedState.error.message)
                awaitComplete()
            }
        }

    @Test
    fun `given calling scope is cancelled mid-flight, when invoked, then flow cancels cleanly`() =
        runTest(testDispatcher) {
            // Given: repository is configured to succeed (but we will cancel before it responds)
            fakeRepository.simulateSuccess(SessionId(1L))

            // When: collect only the first emission then cancel
            useCase(credentials).test {
                val first = awaitItem()
                assertEquals(CameraSessionState.Connecting, first)
                // Cancel the turbine subscription — this simulates the calling scope being cancelled.
                cancelAndIgnoreRemainingEvents()
            }
            // Then: no exception escapes; the flow has been cancelled cleanly.
        }
}
