package dev.po4yka.frameport.camera.domain

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.ImportState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.TransferId
import dev.po4yka.frameport.camera.api.TransferProgress
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.testing.FakeDiagnosticsRepository
import dev.po4yka.frameport.core.testing.FakeTransferRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportObjectUseCaseTest {
    private lateinit var fakeTransferRepository: FakeTransferRepository
    private lateinit var fakeDiagnosticsRepository: FakeDiagnosticsRepository
    private lateinit var importUseCase: ImportObjectUseCase
    private lateinit var cancelUseCase: CancelImportUseCase
    private val testDispatcher = UnconfinedTestDispatcher()

    private val sessionId = SessionId(1L)
    private val handle = CameraObjectHandle(42L)

    @Before
    fun setUp() {
        fakeTransferRepository = FakeTransferRepository()
        fakeDiagnosticsRepository = FakeDiagnosticsRepository()
        importUseCase = ImportObjectUseCase(fakeTransferRepository, fakeDiagnosticsRepository, testDispatcher)
        cancelUseCase = CancelImportUseCase(fakeTransferRepository)
    }

    @Test
    fun `given transfer succeeds, when invoked, then emits Running then Imported`() =
        runTest(testDispatcher) {
            // Given
            val progress = TransferProgress(TransferId(1L), 512L, 1024L)
            fakeTransferRepository.setImportStates(
                ImportState.Running(progress),
                ImportState.Imported("content://media/external/images/1"),
            )

            // When / Then
            importUseCase.invoke(sessionId, handle).test {
                val running = awaitItem()
                assertTrue(running is ImportState.Running)
                assertEquals(progress, (running as ImportState.Running).progress)

                val imported = awaitItem()
                assertTrue(imported is ImportState.Imported)
                assertEquals("content://media/external/images/1", (imported as ImportState.Imported).localUri)

                awaitComplete()
            }
        }

    @Test
    fun `given transfer succeeds, when Imported is emitted, then records exactly one DiagnosticEvent`() =
        runTest(testDispatcher) {
            // Given
            fakeTransferRepository.setImportStates(
                ImportState.Running(TransferProgress(TransferId(1L), 0L, 100L)),
                ImportState.Imported("content://media/external/images/1"),
            )

            // When
            importUseCase.invoke(sessionId, handle).test {
                awaitItem() // Running — no diagnostic
                awaitItem() // Imported — records diagnostic
                awaitComplete()
            }

            // Then
            assertEquals(1, fakeDiagnosticsRepository.recordedEvents.size)
            val event = fakeDiagnosticsRepository.recordedEvents.first()
            assertEquals(dev.po4yka.frameport.camera.api.DiagnosticEvent.Category.Transfer, event.category)
            assertTrue(event.message.contains("${handle.value}"))
        }

    @Test
    fun `given transfer fails, when invoked, then emits Failed and records a DiagnosticEvent`() =
        runTest(testDispatcher) {
            // Given
            val error = FrameportError.MediaUnavailable(null, "object not found on camera")
            fakeTransferRepository.setImportStates(
                ImportState.Failed(error),
            )

            // When / Then
            importUseCase.invoke(sessionId, handle).test {
                val failed = awaitItem()
                assertTrue(failed is ImportState.Failed)
                assertEquals(error, (failed as ImportState.Failed).error)
                awaitComplete()
            }

            // Then: exactly one diagnostic event recorded for the failure
            assertEquals(1, fakeDiagnosticsRepository.recordedEvents.size)
            val event = fakeDiagnosticsRepository.recordedEvents.first()
            assertEquals(dev.po4yka.frameport.camera.api.DiagnosticEvent.Category.Transfer, event.category)
            assertTrue(event.message.contains("${handle.value}"))
        }

    @Test
    fun `given import is in progress, when CancelImportUseCase invoked, then cancels and records cancel in repository`() =
        runTest(testDispatcher) {
            // Given: set up a flow that emits Running then Cancelled
            val transferId = TransferId(7L)
            fakeTransferRepository.setImportStates(
                ImportState.Running(TransferProgress(transferId, 0L, 500L)),
                ImportState.Cancelled,
            )

            // When: collect the import flow fully (to simulate in-progress then cancelled)
            importUseCase.invoke(sessionId, handle).test {
                awaitItem() // Running
                awaitItem() // Cancelled — triggers diagnostic
                awaitComplete()
            }

            // And: invoke cancel (simulates caller cancelling by transferId)
            cancelUseCase(transferId)

            // Then: the transfer repository recorded the cancel call
            assertTrue(fakeTransferRepository.cancelledTransfers.contains(transferId))
        }
}
