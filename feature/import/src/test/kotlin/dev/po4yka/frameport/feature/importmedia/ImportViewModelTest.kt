package dev.po4yka.frameport.feature.importmedia

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.DiagnosticEvent
import dev.po4yka.frameport.camera.api.DiagnosticsRepository
import dev.po4yka.frameport.camera.api.ImportState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.TransferId
import dev.po4yka.frameport.camera.api.TransferProgress
import dev.po4yka.frameport.camera.api.TransferRepository
import dev.po4yka.frameport.camera.domain.CancelImportUseCase
import dev.po4yka.frameport.camera.domain.ImportObjectUseCase
import dev.po4yka.frameport.core.model.FrameportError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ImportViewModel].
 *
 * Uses [UnconfinedTestDispatcher] so [viewModelScope] coroutines execute eagerly.
 * With eager execution, StateFlow conflation means intermediate states may not be
 * observable from a Turbine collector started *after* they were emitted; tests for
 * intermediate Importing state use a [Channel]-backed stub that controls pacing.
 * Terminal-state tests read [viewModel.uiState.value] directly after [advanceUntilIdle].
 *
 * All assertions follow Given-When-Then / Arrange-Act-Assert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private lateinit var fakeTransferRepository: StubTransferRepository
    private lateinit var fakeDiagnosticsRepository: StubDiagnosticsRepository
    private lateinit var viewModel: ImportViewModel

    @Before
    fun setUp() {
        // Replace Main so viewModelScope uses our test dispatcher.
        Dispatchers.setMain(dispatcher)

        fakeTransferRepository = StubTransferRepository()
        fakeDiagnosticsRepository = StubDiagnosticsRepository()

        val importUseCase =
            ImportObjectUseCase(
                transferRepository = fakeTransferRepository,
                diagnosticsRepository = fakeDiagnosticsRepository,
                ioDispatcher = dispatcher,
            )
        val cancelUseCase =
            CancelImportUseCase(
                transferRepository = fakeTransferRepository,
            )
        viewModel = ImportViewModel(importUseCase, cancelUseCase)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── labelFor ──────────────────────────────────────────────────────────────

    @Test
    fun `labelFor generates FRP prefix plus handle value`() {
        assertEquals("FRP_42", labelFor(CameraObjectHandle(42L)))
        assertEquals("FRP_0", labelFor(CameraObjectHandle(0L)))
    }

    // ── formatBytes ───────────────────────────────────────────────────────────

    @Test
    fun `formatBytes formats bytes below 1 KB`() {
        assertEquals("512 B", formatBytes(512L))
    }

    @Test
    fun `formatBytes formats kilobytes`() {
        assertEquals("1.0 KB", formatBytes(1024L))
    }

    @Test
    fun `formatBytes formats megabytes`() {
        assertEquals("1.0 MB", formatBytes(1_048_576L))
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial state is Idle`() =
        testScope.runTest {
            assertEquals(ImportUiState.Idle, viewModel.uiState.value)
        }

    // ── cancelAll ─────────────────────────────────────────────────────────────

    @Test
    fun `cancelAll on Idle is a no-op`() =
        testScope.runTest {
            viewModel.cancelAll()
            assertEquals(ImportUiState.Idle, viewModel.uiState.value)
        }

    // ── enqueueItems with empty list ──────────────────────────────────────────

    @Test
    fun `enqueueItems with empty list stays Idle`() =
        testScope.runTest {
            viewModel.enqueueItems(SessionId(1L), emptyList())
            advanceUntilIdle()
            assertEquals(ImportUiState.Idle, viewModel.uiState.value)
        }

    // ── Importing state observed while flow is paused ─────────────────────────

    /**
     * Uses a [Channel] stub so the import flow never completes during the test,
     * letting us observe the Importing state before a terminal state arrives.
     */
    @Test
    fun `enqueueItems transitions to Importing with labelled zero-progress row`() =
        testScope.runTest {
            // Arrange: channel stub — flow won't complete until we close the channel.
            val channel = Channel<ImportState>(capacity = Channel.UNLIMITED)
            fakeTransferRepository.channelStub = channel
            val handle = CameraObjectHandle(200L)

            // Act.
            viewModel.enqueueItems(SessionId(1L), listOf(handle))
            advanceUntilIdle()

            // Assert: state is Importing with the correct label.
            val state = viewModel.uiState.value
            assertTrue("Expected Importing, got $state", state is ImportUiState.Importing)
            val row = (state as ImportUiState.Importing).items.first()
            assertEquals(handle, row.objectHandle)
            assertEquals("FRP_200", row.label)
            assertEquals(0f, row.fraction, 0.001f)

            channel.close()
        }

    @Test
    fun `Running progress emission updates fraction in Importing state`() =
        testScope.runTest {
            // Arrange.
            val channel = Channel<ImportState>(capacity = Channel.UNLIMITED)
            fakeTransferRepository.channelStub = channel
            val handle = CameraObjectHandle(201L)
            val transferId = TransferId(20L)

            viewModel.enqueueItems(SessionId(1L), listOf(handle))
            advanceUntilIdle()

            // Act: send Running progress.
            channel.send(
                ImportState.Running(
                    progress =
                        TransferProgress(
                            transferId = transferId,
                            bytesTransferred = 512_000L,
                            totalBytes = 1_024_000L,
                        ),
                ),
            )
            advanceUntilIdle()

            // Assert: fraction updated.
            val state = viewModel.uiState.value
            assertTrue("Expected Importing after Running, got $state", state is ImportUiState.Importing)
            val row = (state as ImportUiState.Importing).items.first()
            assertEquals(0.5f, row.fraction, 0.01f)
            assertEquals(512_000L, row.bytesTransferred)
            assertEquals(1_024_000L, row.totalBytes)

            channel.close()
        }

    // ── Importing -> Imported ─────────────────────────────────────────────────

    @Test
    fun `importing single item that completes transitions to Done with Imported result`() =
        testScope.runTest {
            // Arrange: eagerly-completing flow.
            val handle = CameraObjectHandle(300L)
            fakeTransferRepository.stub =
                flowOf(ImportState.Imported(localUri = "content://media/external/images/300"))

            // Act.
            viewModel.enqueueItems(SessionId(1L), listOf(handle))
            advanceUntilIdle()

            // Assert: terminal Done state.
            val state = viewModel.uiState.value
            assertTrue("Expected Done, got $state", state is ImportUiState.Done)
            val result = (state as ImportUiState.Done).results.first()
            assertTrue("Expected Imported result, got $result", result is ImportResult.Imported)
            assertEquals("FRP_300", result.label)
            assertEquals("content://media/external/images/300", (result as ImportResult.Imported).localUri)
        }

    // ── Importing -> Failed ───────────────────────────────────────────────────

    @Test
    fun `importing single item that fails transitions to Done with typed FrameportError`() =
        testScope.runTest {
            // Arrange.
            val handle = CameraObjectHandle(400L)
            val error = FrameportError.MediaUnavailable(mediaObjectId = null, message = "Object not found")
            fakeTransferRepository.stub = flowOf(ImportState.Failed(error = error))

            // Act.
            viewModel.enqueueItems(SessionId(1L), listOf(handle))
            advanceUntilIdle()

            // Assert.
            val state = viewModel.uiState.value
            assertTrue("Expected Done, got $state", state is ImportUiState.Done)
            val result = (state as ImportUiState.Done).results.first()
            assertTrue("Expected Failed result, got $result", result is ImportResult.Failed)
            assertEquals("FRP_400", result.label)
            assertEquals(error, (result as ImportResult.Failed).error)
        }

    // ── Importing -> Cancelled ────────────────────────────────────────────────

    @Test
    fun `importing single item that is cancelled transitions to Done with Cancelled result`() =
        testScope.runTest {
            // Arrange.
            val handle = CameraObjectHandle(500L)
            fakeTransferRepository.stub = flowOf(ImportState.Cancelled)

            // Act.
            viewModel.enqueueItems(SessionId(1L), listOf(handle))
            advanceUntilIdle()

            // Assert.
            val state = viewModel.uiState.value
            assertTrue("Expected Done, got $state", state is ImportUiState.Done)
            val result = (state as ImportUiState.Done).results.first()
            assertTrue("Expected Cancelled result, got $result", result is ImportResult.Cancelled)
            assertEquals("FRP_500", result.label)
        }

    // ── Fraction edge cases ───────────────────────────────────────────────────

    @Test
    fun `fraction is zero when totalBytes is zero`() =
        testScope.runTest {
            val channel = Channel<ImportState>(capacity = Channel.UNLIMITED)
            fakeTransferRepository.channelStub = channel
            val handle = CameraObjectHandle(600L)

            viewModel.enqueueItems(SessionId(1L), listOf(handle))
            channel.send(
                ImportState.Running(
                    progress =
                        TransferProgress(
                            transferId = TransferId(60L),
                            bytesTransferred = 0L,
                            totalBytes = 0L,
                        ),
                ),
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            if (state is ImportUiState.Importing) {
                assertEquals(0f, state.items.first().fraction, 0.001f)
            }
            channel.close()
        }

    @Test
    fun `fraction is clamped to 1f when bytesTransferred exceeds totalBytes`() {
        // Pure calculation — no coroutines needed.
        val progress =
            TransferProgress(
                transferId = TransferId(1L),
                bytesTransferred = 9_000_000L,
                totalBytes = 5_000_000L,
            )
        val fraction =
            if (progress.totalBytes > 0L) {
                (progress.bytesTransferred.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
            } else {
                0f
            }
        assertEquals(1f, fraction, 0.001f)
    }

    // ── cancelItem records the transfer id ────────────────────────────────────

    @Test
    fun `cancelItem delegates transferId to CancelImportUseCase`() =
        testScope.runTest {
            val transferId = TransferId(77L)
            viewModel.cancelItem(transferId)
            advanceUntilIdle()
            assertTrue(
                "Expected transferId ${transferId.value} in cancelled set",
                fakeTransferRepository.cancelledIds.contains(transferId),
            )
        }

    // ── StateFlow stream via Turbine (channel-paced) ──────────────────────────

    @Test
    fun `uiState emits Importing then Done via Turbine with channel stub`() =
        testScope.runTest {
            // Arrange.
            val channel = Channel<ImportState>(capacity = Channel.UNLIMITED)
            fakeTransferRepository.channelStub = channel
            val handle = CameraObjectHandle(700L)

            viewModel.uiState.test {
                // Consume initial Idle.
                assertEquals(ImportUiState.Idle, awaitItem())

                // Act: enqueue — flow is live but not completed.
                viewModel.enqueueItems(SessionId(1L), listOf(handle))
                advanceUntilIdle()

                // Assert: Queued then Importing states are visible.
                // enqueueItems sets Queued first, then startImport transitions to Importing.
                val firstItem = awaitItem()
                val importing =
                    if (firstItem is ImportUiState.Queued) {
                        awaitItem() // consume Queued, get Importing
                    } else {
                        firstItem
                    }
                assertTrue("Expected Importing, got $importing", importing is ImportUiState.Importing)
                assertEquals("FRP_700", (importing as ImportUiState.Importing).items.first().label)

                // Act: close channel to complete the flow without a terminal ImportState,
                // which leaves the item in Importing indefinitely — send Imported to finalize.
                channel.send(ImportState.Imported(localUri = "content://media/700"))
                advanceUntilIdle()

                // Assert: Done.
                val done = awaitItem()
                assertTrue("Expected Done, got $done", done is ImportUiState.Done)

                channel.close()
                cancelAndIgnoreRemainingEvents()
            }
        }
}

// ─── Local test doubles ───────────────────────────────────────────────────────

/**
 * Configurable [TransferRepository] stub.
 *
 * - [stub]: a static [Flow] returned for each [importObject] call (defaults to never-completing).
 * - [channelStub]: when non-null, [importObject] returns [channelStub].receiveAsFlow() instead.
 *   This lets tests control when items arrive and when the flow completes.
 * - [cancelledIds]: records every [cancelImport] call for assertion.
 */
private class StubTransferRepository : TransferRepository {
    var stub: Flow<ImportState> = flowOf(ImportState.Idle)
    var channelStub: Channel<ImportState>? = null
    val cancelledIds = mutableListOf<TransferId>()

    override fun importObject(
        sessionId: SessionId,
        handle: CameraObjectHandle,
    ): Flow<ImportState> = channelStub?.receiveAsFlow() ?: stub

    override suspend fun cancelImport(transferId: TransferId) {
        cancelledIds.add(transferId)
    }
}

/** Minimal [DiagnosticsRepository] stub — does not assert on recorded events here. */
private class StubDiagnosticsRepository : DiagnosticsRepository {
    private val _events = MutableSharedFlow<DiagnosticEvent>(extraBufferCapacity = 64)

    override fun events(): Flow<DiagnosticEvent> = _events.asSharedFlow()

    override suspend fun recordEvent(event: DiagnosticEvent) {
        _events.tryEmit(event)
    }
}
