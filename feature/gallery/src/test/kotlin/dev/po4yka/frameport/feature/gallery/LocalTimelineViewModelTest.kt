package dev.po4yka.frameport.feature.gallery

import app.cash.turbine.test
import dev.po4yka.frameport.core.model.ImportSession
import dev.po4yka.frameport.core.storage.timeline.LocalTimelineStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * JVM unit tests for [LocalTimelineViewModel].
 *
 * Uses [FakeLocalTimelineStore] — a [MutableStateFlow]-backed stub — to drive [observeSessions]
 * without any Room or Android dependency.
 *
 * Dispatcher discipline:
 * - A fresh [StandardTestDispatcher] is installed on [Dispatchers.Main] per test via [setUp] /
 *   [tearDown]. Using StandardTestDispatcher keeps time-sensitive [SharingStarted.WhileSubscribed]
 *   deterministic and prevents coroutine leaks.
 * - [advanceUntilIdle] is called to drain the stateIn coroutine before asserting.
 *
 * Test structure: Arrange-Act-Assert (AAA).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalTimelineViewModelTest {
    // JUnit4 creates a new test-class instance per @Test method, so per-instance val is safe.
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeStore: FakeLocalTimelineStore
    private lateinit var viewModel: LocalTimelineViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeStore = FakeLocalTimelineStore()
        viewModel = LocalTimelineViewModel(localTimelineStore = fakeStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // cancel-safe: runTest/StandardTestDispatcher; LocalTimelineStore.observeSessions() is a
    // Room-backed Flow — cancellation unsubscribes the InvalidationTracker observer cleanly.

    // ─── Initial state ────────────────────────────────────────────────────────

    @Test
    fun `initial uiState is Loading before first emission`() =
        runTest(testDispatcher) {
            // The stateIn initial value is Loading; before advanceUntilIdle the upstream has
            // not yet been collected.
            assertEquals(LocalTimelineUiState.Loading, viewModel.uiState.value)
        }

    // ─── Loaded state ─────────────────────────────────────────────────────────

    @Test
    fun `three sessions from store produce Loaded state with three sessions`() =
        runTest(testDispatcher) {
            // Arrange: store holds three sessions in descending endedAtEpochMs order
            val sessions =
                listOf(
                    buildSession("session:3", endedAt = 3000L),
                    buildSession("session:2", endedAt = 2000L),
                    buildSession("session:1", endedAt = 1000L),
                )
            fakeStore.emit(sessions)

            viewModel.uiState.test {
                awaitItem() // Loading sentinel

                advanceUntilIdle()
                val loaded = awaitItem() as LocalTimelineUiState.Loaded

                assertEquals(3, loaded.sessions.size)
                assertEquals("session:3", loaded.sessions[0].sessionKey)
                assertEquals("session:2", loaded.sessions[1].sessionKey)
                assertEquals("session:1", loaded.sessions[2].sessionKey)
                assertFalse(loaded.isEmpty)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `single session from store produces Loaded state`() =
        runTest(testDispatcher) {
            // Arrange
            fakeStore.emit(listOf(buildSession("session:1", endedAt = 1000L)))

            viewModel.uiState.test {
                awaitItem() // Loading

                advanceUntilIdle()
                val loaded = awaitItem() as LocalTimelineUiState.Loaded

                assertEquals(1, loaded.sessions.size)
                assertFalse(loaded.isEmpty)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Empty state ──────────────────────────────────────────────────────────

    @Test
    fun `empty store emission produces Empty state`() =
        runTest(testDispatcher) {
            // Arrange: store emits empty list from the start
            fakeStore.emit(emptyList())

            viewModel.uiState.test {
                awaitItem() // Loading

                advanceUntilIdle()
                val state = awaitItem()

                assertEquals(LocalTimelineUiState.Empty, state)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `empty state is not an error state`() =
        runTest(testDispatcher) {
            // Arrange
            fakeStore.emit(emptyList())

            viewModel.uiState.test {
                awaitItem() // Loading
                advanceUntilIdle()
                val state = awaitItem()

                assertTrue(state is LocalTimelineUiState.Empty)
                assertFalse(state is LocalTimelineUiState.Loaded)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Order guarantee ──────────────────────────────────────────────────────

    @Test
    fun `sessions are delivered in the order provided by the store`() =
        runTest(testDispatcher) {
            // The ViewModel does NOT sort — it delegates ordering to RoomLocalTimelineStore.
            // This test verifies the ViewModel passes through order unchanged.
            val sessions =
                listOf(
                    buildSession("session:99", endedAt = 9900L),
                    buildSession("day:2024-01-01", endedAt = 1000L),
                )
            fakeStore.emit(sessions)

            viewModel.uiState.test {
                awaitItem() // Loading
                advanceUntilIdle()
                val loaded = awaitItem() as LocalTimelineUiState.Loaded

                assertEquals("session:99", loaded.sessions[0].sessionKey)
                assertEquals("day:2024-01-01", loaded.sessions[1].sessionKey)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Reactive update ──────────────────────────────────────────────────────

    @Test
    fun `store emitting a new list updates uiState`() =
        runTest(testDispatcher) {
            // Arrange: start with one session
            fakeStore.emit(listOf(buildSession("session:1", endedAt = 1000L)))

            viewModel.uiState.test {
                awaitItem() // Loading
                advanceUntilIdle()
                val first = awaitItem() as LocalTimelineUiState.Loaded
                assertEquals(1, first.sessions.size)

                // Act: store gains a second session
                fakeStore.emit(
                    listOf(
                        buildSession("session:2", endedAt = 2000L),
                        buildSession("session:1", endedAt = 1000L),
                    ),
                )
                advanceUntilIdle()
                val second = awaitItem() as LocalTimelineUiState.Loaded
                assertEquals(2, second.sessions.size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Cancellation / no coroutine leak ─────────────────────────────────────

    @Test
    fun `runTest completes without coroutine leak when ViewModel is unused`() =
        runTest(testDispatcher) {
            // Simply creating the ViewModel and collecting for one tick must not leave any
            // orphaned coroutines after runTest finishes. StandardTestDispatcher enforces this.
            viewModel.uiState.test {
                awaitItem() // Loading
                cancelAndIgnoreRemainingEvents()
            }
            // runTest will fail if any coroutine is still active at this point.
        }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun buildSession(
        sessionKey: String,
        endedAt: Long,
        objectCount: Int = 3,
        totalBytes: Long = 1024L,
        thumbnailUris: List<String> = listOf("content://media/1"),
        transportLabel: String = "Unknown",
    ) = ImportSession(
        sessionKey = sessionKey,
        startedAtEpochMs = endedAt - 100L,
        endedAtEpochMs = endedAt,
        objectCount = objectCount,
        totalBytes = totalBytes,
        thumbnailUris = thumbnailUris,
        transportLabel = transportLabel,
    )
}

// ─── Fake ────────────────────────────────────────────────────────────────────

/**
 * In-memory [LocalTimelineStore] backed by a [MutableStateFlow].
 *
 * [emit] replaces the current list and triggers a new flow emission, replicating Room's
 * InvalidationTracker behaviour without any database dependency.
 */
private class FakeLocalTimelineStore : LocalTimelineStore {
    private val _sessions = MutableStateFlow<List<ImportSession>>(emptyList())

    /** Push a new list of sessions to all active [observeSessions] collectors. */
    fun emit(sessions: List<ImportSession>) {
        _sessions.value = sessions
    }

    // cancel-safe: MutableStateFlow collection cancels cleanly via coroutine cancellation.
    override fun observeSessions(): Flow<List<ImportSession>> = _sessions.asStateFlow()
}
