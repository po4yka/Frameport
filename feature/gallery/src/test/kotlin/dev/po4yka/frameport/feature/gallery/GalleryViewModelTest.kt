package dev.po4yka.frameport.feature.gallery

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.CameraMediaFormat
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.domain.ListMediaUseCase
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.model.TransportKind
import dev.po4yka.frameport.core.testing.FakeMediaRepository
import dev.po4yka.frameport.core.testing.fakeCameraMediaObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [GalleryViewModel].
 *
 * Uses [FakeMediaRepository] + [ListMediaUseCase] without Android components.
 *
 * [UnconfinedTestDispatcher] is used so [viewModelScope] coroutines execute eagerly and
 * [StateFlow] emissions are visible immediately after [GalleryViewModel.load] returns —
 * eliminating the deduplication race that arises with [StandardTestDispatcher] when
 * [load] re-sets state to [GalleryUiState.Loading] (same value as initial, collapsed by StateFlow).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeMediaRepository: FakeMediaRepository
    private lateinit var viewModel: GalleryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeMediaRepository = FakeMediaRepository()
        val useCase =
            ListMediaUseCase(
                mediaRepository = fakeMediaRepository,
                ioDispatcher = testDispatcher,
            )
        viewModel =
            GalleryViewModel(
                listMediaUseCase = useCase,
                mediaRepository = fakeMediaRepository,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── Initial state ────────────────────────────────────────────────────────

    @Test
    fun `initial state is Loading`() =
        runTest {
            assertEquals(GalleryUiState.Loading, viewModel.uiState.value)
        }

    // ─── load() — happy path ──────────────────────────────────────────────────

    @Test
    fun `load with non-empty list transitions to Loaded`() =
        runTest {
            val objects =
                listOf(
                    fakeCameraMediaObject(handle = 1L, format = CameraMediaFormat.Jpeg),
                    fakeCameraMediaObject(handle = 2L, format = CameraMediaFormat.Raf),
                )
            fakeMediaRepository.setMedia(objects)

            // With UnconfinedTestDispatcher, load() runs to completion before returning.
            viewModel.load(SessionId(10L))

            val loaded = viewModel.uiState.value as GalleryUiState.Loaded
            assertEquals(2, loaded.items.size)
            assertTrue(loaded.selectedHandles.isEmpty())
            assertEquals("FRP_1.jpg", loaded.items[0].label)
            assertEquals("FRP_2.raf", loaded.items[1].label)
        }

    @Test
    fun `load emits Loading then Loaded via Turbine`() =
        runTest {
            val objects = listOf(fakeCameraMediaObject(handle = 1L, format = CameraMediaFormat.Jpeg))
            fakeMediaRepository.setMedia(objects)

            viewModel.uiState.test {
                // With UnconfinedTestDispatcher the initial value is emitted immediately.
                assertEquals(GalleryUiState.Loading, awaitItem())

                viewModel.load(SessionId(10L))
                // load() sets Loading (deduplicated by StateFlow, no new emission),
                // then sets Loaded (different value — emitted).
                val loaded = awaitItem() as GalleryUiState.Loaded
                assertEquals(1, loaded.items.size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `load with empty list transitions to Empty`() =
        runTest {
            fakeMediaRepository.setMedia(emptyList())

            viewModel.load(SessionId(10L))

            assertEquals(GalleryUiState.Empty, viewModel.uiState.value)
        }

    // ─── load() — error path ──────────────────────────────────────────────────

    @Test
    fun `load with TransportUnavailable error maps to GalleryError TransportUnavailable`() =
        runTest {
            fakeMediaRepository.failWith(
                FrameportError.TransportUnavailable(
                    transportKind = TransportKind.WifiPtpIp,
                    message = "Wi-Fi not connected",
                ),
            )

            viewModel.load(SessionId(10L))

            val error = viewModel.uiState.value as GalleryUiState.Error
            assertEquals(GalleryError.TransportUnavailable, error.error)
        }

    @Test
    fun `load with ProtocolUnavailable error maps to GalleryError ProtocolFailure`() =
        runTest {
            fakeMediaRepository.failWith(
                FrameportError.ProtocolUnavailable(message = "handshake rejected"),
            )

            viewModel.load(SessionId(10L))

            val error = viewModel.uiState.value as GalleryUiState.Error
            assertEquals(GalleryError.ProtocolFailure, error.error)
        }

    @Test
    fun `load with MediaUnavailable error maps to GalleryError MediaUnavailable`() =
        runTest {
            fakeMediaRepository.failWith(
                FrameportError.MediaUnavailable(
                    mediaObjectId = null,
                    message = "Object not found",
                ),
            )

            viewModel.load(SessionId(10L))

            val error = viewModel.uiState.value as GalleryUiState.Error
            assertTrue(error.error is GalleryError.MediaUnavailable)
            assertEquals("Object not found", (error.error as GalleryError.MediaUnavailable).detail)
        }

    @Test
    fun `load with PermissionDenied error maps to GalleryError NoSession`() =
        runTest {
            fakeMediaRepository.failWith(
                FrameportError.PermissionDenied(
                    permission = "android.permission.NEARBY_WIFI_DEVICES",
                    message = "Permission denied",
                ),
            )

            viewModel.load(SessionId(10L))

            val error = viewModel.uiState.value as GalleryUiState.Error
            assertEquals(GalleryError.NoSession, error.error)
        }

    @Test
    fun `load with Unknown error maps to GalleryError Unknown`() =
        runTest {
            fakeMediaRepository.failWith(
                FrameportError.Unknown(message = "something unexpected"),
            )

            viewModel.load(SessionId(10L))

            val error = viewModel.uiState.value as GalleryUiState.Error
            assertTrue(error.error is GalleryError.Unknown)
        }

    // ─── Selection ────────────────────────────────────────────────────────────

    @Test
    fun `toggleSelection selects an unselected item`() =
        runTest {
            fakeMediaRepository.setMedia(listOf(fakeCameraMediaObject(handle = 1L)))
            viewModel.load(SessionId(10L))

            viewModel.toggleSelection(CameraObjectHandle(1L))

            val state = viewModel.uiState.value as GalleryUiState.Loaded
            assertTrue(CameraObjectHandle(1L) in state.selectedHandles)
            assertTrue(state.items.first().isSelected)
        }

    @Test
    fun `toggleSelection deselects an already-selected item`() =
        runTest {
            fakeMediaRepository.setMedia(listOf(fakeCameraMediaObject(handle = 1L)))
            viewModel.load(SessionId(10L))

            viewModel.toggleSelection(CameraObjectHandle(1L))
            viewModel.toggleSelection(CameraObjectHandle(1L))

            val state = viewModel.uiState.value as GalleryUiState.Loaded
            assertFalse(CameraObjectHandle(1L) in state.selectedHandles)
            assertFalse(state.items.first().isSelected)
        }

    @Test
    fun `selectAll marks all items selected`() =
        runTest {
            fakeMediaRepository.setMedia(
                listOf(
                    fakeCameraMediaObject(handle = 1L),
                    fakeCameraMediaObject(handle = 2L),
                    fakeCameraMediaObject(handle = 3L),
                ),
            )
            viewModel.load(SessionId(10L))

            viewModel.selectAll()

            val state = viewModel.uiState.value as GalleryUiState.Loaded
            assertEquals(3, state.selectedHandles.size)
            assertTrue(state.allSelected)
            assertTrue(state.items.all { it.isSelected })
        }

    @Test
    fun `clearSelection deselects all items`() =
        runTest {
            fakeMediaRepository.setMedia(
                listOf(
                    fakeCameraMediaObject(handle = 1L),
                    fakeCameraMediaObject(handle = 2L),
                ),
            )
            viewModel.load(SessionId(10L))

            viewModel.selectAll()
            viewModel.clearSelection()

            val state = viewModel.uiState.value as GalleryUiState.Loaded
            assertTrue(state.selectedHandles.isEmpty())
            assertTrue(state.items.none { it.isSelected })
        }

    @Test
    fun `selectionCount reflects number of selected handles`() =
        runTest {
            fakeMediaRepository.setMedia(
                listOf(
                    fakeCameraMediaObject(handle = 1L),
                    fakeCameraMediaObject(handle = 2L),
                    fakeCameraMediaObject(handle = 3L),
                ),
            )
            viewModel.load(SessionId(10L))

            viewModel.toggleSelection(CameraObjectHandle(1L))
            viewModel.toggleSelection(CameraObjectHandle(3L))

            val state = viewModel.uiState.value as GalleryUiState.Loaded
            assertEquals(2, state.selectionCount)
            assertFalse(state.allSelected)
        }

    @Test
    fun `toggleSelection is no-op when state is not Loaded`() =
        runTest {
            // State is still Loading (never called load)
            viewModel.toggleSelection(CameraObjectHandle(1L))
            assertEquals(GalleryUiState.Loading, viewModel.uiState.value)
        }

    @Test
    fun `selectAll is no-op when state is not Loaded`() =
        runTest {
            viewModel.selectAll()
            assertEquals(GalleryUiState.Loading, viewModel.uiState.value)
        }

    @Test
    fun `clearSelection is no-op when state is not Loaded`() =
        runTest {
            viewModel.clearSelection()
            assertEquals(GalleryUiState.Loading, viewModel.uiState.value)
        }

    // ─── Label generation ─────────────────────────────────────────────────────

    @Test
    fun `toGalleryItem generates correct labels for all formats`() {
        val cases =
            mapOf(
                CameraMediaFormat.Jpeg to "FRP_1.jpg",
                CameraMediaFormat.Raf to "FRP_1.raf",
                CameraMediaFormat.Heif to "FRP_1.heif",
                CameraMediaFormat.Mov to "FRP_1.mov",
                CameraMediaFormat.Unknown to "FRP_1.bin",
            )
        cases.forEach { (format, expectedLabel) ->
            val item = fakeCameraMediaObject(handle = 1L, format = format).toGalleryItem()
            assertEquals("Format $format", expectedLabel, item.label)
        }
    }

    // ─── Retry ────────────────────────────────────────────────────────────────

    @Test
    fun `load can be retried after an error and succeeds`() =
        runTest {
            fakeMediaRepository.failWith(FrameportError.Unknown(message = "network error"))
            viewModel.load(SessionId(10L))

            assertTrue(viewModel.uiState.value is GalleryUiState.Error)

            // Recover and retry
            fakeMediaRepository.setMedia(listOf(fakeCameraMediaObject(handle = 99L)))
            viewModel.load(SessionId(10L))

            val loaded = viewModel.uiState.value as GalleryUiState.Loaded
            assertEquals(1, loaded.items.size)
        }
}
