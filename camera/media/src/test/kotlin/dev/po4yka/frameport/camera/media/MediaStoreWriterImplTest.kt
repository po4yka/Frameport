package dev.po4yka.frameport.camera.media

import android.os.ParcelFileDescriptor
import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.CameraMediaFormat
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.ImportState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.TransferId
import dev.po4yka.frameport.camera.api.TransferProgress
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.storage.catalog.ImportCatalog
import dev.po4yka.frameport.core.storage.catalog.ImportCatalogEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [MediaStoreWriterImpl].
 *
 * Uses a [FakeMediaStoreGateway] to avoid touching Android ContentResolver / MediaStore.
 * [FujiNativeSdk] and [ImportCatalog] are mocked via MockK.
 * No Robolectric required.
 */
class MediaStoreWriterImplTest {
    private val sessionId = SessionId(1L)
    private val handle = CameraObjectHandle(12345678L)
    private val transferId = TransferId(handle.value)
    private val stubProgress = TransferProgress(transferId, 0L, 0L)

    // Fixed clock pinned to 2024-03-15T12:00:00Z for deterministic date assertions.
    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2024-03-15T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var fakeGateway: FakeMediaStoreGateway
    private lateinit var mockSdk: FujiNativeSdk
    private lateinit var mockCatalog: ImportCatalog
    private lateinit var writer: MediaStoreWriterImpl

    @Before
    fun setUp() {
        fakeGateway = FakeMediaStoreGateway()
        mockSdk = mockk()
        mockCatalog = mockk(relaxed = true)
        writer =
            MediaStoreWriterImpl(
                fujiNativeSdk = mockSdk,
                ioDispatcher = UnconfinedTestDispatcher(),
                importCatalog = mockCatalog,
                formatMapper = FujiFormatMimeMapper(),
                gateway = fakeGateway,
                clock = fixedClock,
            )
    }

    // ─── Success path ─────────────────────────────────────────────────────────

    @Test
    fun `success path emits Running then Imported and records catalog entry`() =
        runTest {
            every { mockSdk.downloadObjectToFd(sessionId, handle, any()) } returns flowOf(stubProgress)

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Jpeg).test {
                val running = awaitItem()
                assertTrue(running is ImportState.Running)
                assertEquals(stubProgress, (running as ImportState.Running).progress)

                val imported = awaitItem()
                assertTrue(imported is ImportState.Imported)
                val uri = (imported as ImportState.Imported).localUri
                assertEquals(FakeMediaStoreGateway.STUB_URI, uri)

                awaitComplete()
            }

            // Catalog must have been recorded
            coVerify { mockCatalog.recordImport(any(), any(), any(), any(), any(), any(), any(), any()) }
            // Pending row must be finalised (not deleted)
            assertTrue(fakeGateway.finalised)
            assertFalse(fakeGateway.deleted)
            // PFD must be closed
            assertTrue(fakeGateway.pfdClosed)
        }

    @Test
    fun `success path display name follows FRP underscore handle dot ext pattern`() =
        runTest {
            every { mockSdk.downloadObjectToFd(sessionId, handle, any()) } returns flowOf(stubProgress)

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Jpeg).test {
                awaitItem() // Running
                awaitItem() // Imported
                awaitComplete()
            }

            assertEquals("FRP_${handle.value}.jpg", fakeGateway.lastDisplayName)
        }

    @Test
    fun `raf format produces correct mime and extension`() =
        runTest {
            every { mockSdk.downloadObjectToFd(sessionId, handle, any()) } returns flowOf(stubProgress)

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Raf).test {
                awaitItem()
                awaitItem()
                awaitComplete()
            }

            assertEquals("image/x-fuji-raf", fakeGateway.lastMimeType)
            assertEquals("FRP_${handle.value}.raf", fakeGateway.lastDisplayName)
        }

    // ─── Failure: SDK throws ──────────────────────────────────────────────────

    @Test
    fun `sdk exception emits Failed with redacted Unknown error and deletes pending row`() =
        runTest {
            every { mockSdk.downloadObjectToFd(sessionId, handle, any()) } returns
                flow {
                    throw RuntimeException("camera disconnected")
                }

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Jpeg).test {
                val failed = awaitItem()
                assertTrue(failed is ImportState.Failed)
                val error = (failed as ImportState.Failed).error
                assertTrue(error is FrameportError.Unknown)
                // Error message must be a fixed, category-only string — no raw exception
                // message AND no exception class name (privacy / category-only invariant).
                assertEquals("Transfer failed", (error as FrameportError.Unknown).message)
                awaitComplete()
            }

            // Pending row must be deleted, never finalised
            assertTrue(fakeGateway.deleted)
            assertFalse(fakeGateway.finalised)
            assertTrue(fakeGateway.pfdClosed)
            // Catalog must NOT be recorded on failure
            coVerify(exactly = 0) { mockCatalog.recordImport(any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    // ─── Failure: gateway insert returns null ─────────────────────────────────

    @Test
    fun `null from insertPendingRow emits Failed without opening fd`() =
        runTest {
            fakeGateway.insertShouldReturnNull = true

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Jpeg).test {
                val failed = awaitItem()
                assertTrue(failed is ImportState.Failed)
                val error = (failed as ImportState.Failed).error
                assertTrue(error is FrameportError.Unknown)
                awaitComplete()
            }

            assertFalse(fakeGateway.fdOpened)
            assertFalse(fakeGateway.deleted)
            coVerify(exactly = 0) { mockCatalog.recordImport(any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    // ─── Failure: openWriteFd returns null ────────────────────────────────────

    @Test
    fun `null from openWriteFd emits Failed and deletes pending row`() =
        runTest {
            fakeGateway.openFdShouldReturnNull = true

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Jpeg).test {
                val failed = awaitItem()
                assertTrue(failed is ImportState.Failed)
                awaitComplete()
            }

            assertTrue(fakeGateway.deleted)
            assertFalse(fakeGateway.finalised)
            coVerify(exactly = 0) { mockCatalog.recordImport(any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    // ─── Privacy invariants ───────────────────────────────────────────────────

    @Test
    fun `RELATIVE_PATH contains Pictures-Frameport prefix and no serial`() =
        runTest {
            every { mockSdk.downloadObjectToFd(sessionId, handle, any()) } returns flowOf(stubProgress)

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Jpeg).test {
                awaitItem()
                awaitItem()
                awaitComplete()
            }

            assertTrue(
                "RELATIVE_PATH should start with Pictures/Frameport/",
                fakeGateway.lastRelativePath.startsWith("Pictures/Frameport/"),
            )
        }

    @Test
    fun `video format produces Movies prefix in RELATIVE_PATH`() =
        runTest {
            every { mockSdk.downloadObjectToFd(sessionId, handle, any()) } returns flowOf(stubProgress)

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Mov).test {
                awaitItem()
                awaitItem()
                awaitComplete()
            }

            assertTrue(
                "RELATIVE_PATH should start with Movies/Frameport/",
                fakeGateway.lastRelativePath.startsWith("Movies/Frameport/"),
            )
        }

    @Test
    fun `Imported localUri is a string not android-net-Uri`() =
        runTest {
            every { mockSdk.downloadObjectToFd(sessionId, handle, any()) } returns flowOf(stubProgress)

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Jpeg).test {
                awaitItem()
                val imported = awaitItem() as ImportState.Imported
                // The type must be String (camera:api is pure JVM; no android.net.Uri)
                assertEquals(String::class, imported.localUri::class)
                awaitComplete()
            }
        }

    @Test
    fun `RELATIVE_PATH date segment matches the injected clock not wall clock`() =
        runTest {
            // The fixed clock is pinned to 2024-03-15. Verify the path encodes exactly that date
            // regardless of when the test runs — proving Clock injection is used (Low finding).
            every { mockSdk.downloadObjectToFd(sessionId, handle, any()) } returns flowOf(stubProgress)

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Jpeg).test {
                awaitItem() // Running
                awaitItem() // Imported
                awaitComplete()
            }

            assertTrue(
                "RELATIVE_PATH must end with the fixed-clock date 2024-03-15, got: ${fakeGateway.lastRelativePath}",
                fakeGateway.lastRelativePath.endsWith("2024-03-15"),
            )
        }

    // ─── Cancellation ──────────────────────────────────────────────────────

    @Test
    fun `cancellation deletes pending row closes pfd and does not finalize`() =
        runTest {
            // Arrange: SDK flow emits one progress then suspends (so cancellation can race it).
            // We use an infinite flow; the test cancels the Turbine collector after the first item.
            every { mockSdk.downloadObjectToFd(sessionId, handle, any()) } returns
                flow {
                    emit(stubProgress)
                    // Suspend indefinitely to simulate an in-progress transfer.
                    awaitCancellation()
                }

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Jpeg).test {
                // First item: Running
                val first = awaitItem()
                assertTrue(first is ImportState.Running)
                // Cancel the Turbine collector -- this propagates CancellationException into the flow.
                cancel()
            }

            // Pending row must be deleted; it must not have been finalised.
            assertTrue("deletePending must be called on cancellation", fakeGateway.deleted)
            assertFalse("finalizePending must NOT be called on cancellation", fakeGateway.finalised)
            // PFD must be closed even on cancellation (Android owns the fd, not Rust).
            assertTrue("pfd must be closed on cancellation", fakeGateway.pfdClosed)
            // Catalog must not be recorded on cancellation.
            coVerify(exactly = 0) { mockCatalog.recordImport(any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    // ─── fd ownership invariant (G1 leak guard) ────────────────────────────

    @Test
    fun `pfd detachFd is never called on success path`() =
        runTest {
            // Arrange: the fake gateway hands a pfd whose detachFd() flips a flag.
            // If MediaStoreWriterImpl ever calls detachFd(), the test fails (G1 leak guard).
            every { mockSdk.downloadObjectToFd(sessionId, handle, any()) } returns flowOf(stubProgress)

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Jpeg).test {
                awaitItem() // Running
                awaitItem() // Imported
                awaitComplete()
            }

            assertFalse(
                "detachFd() must NEVER be called (G1: Rust borrows the fd; Android owns it)",
                fakeGateway.detachFdCalled,
            )
        }

    @Test
    fun `pfd detachFd is never called on failure path`() =
        runTest {
            every { mockSdk.downloadObjectToFd(sessionId, handle, any()) } returns
                flow { throw RuntimeException("sdk error") }

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Jpeg).test {
                awaitItem() // Failed
                awaitComplete()
            }

            assertFalse(
                "detachFd() must NEVER be called on failure (G1: Rust borrows the fd; Android owns it)",
                fakeGateway.detachFdCalled,
            )
        }

    @Test
    fun `pfd detachFd is never called on cancellation path`() =
        runTest {
            every { mockSdk.downloadObjectToFd(sessionId, handle, any()) } returns
                flow {
                    emit(stubProgress)
                    awaitCancellation()
                }

            writer.importToMediaStore(sessionId, handle, CameraMediaFormat.Jpeg).test {
                awaitItem() // Running
                cancel()
            }

            assertFalse(
                "detachFd() must NEVER be called on cancellation (G1: Rust borrows the fd; Android owns it)",
                fakeGateway.detachFdCalled,
            )
        }

    // ─── Fake gateway ─────────────────────────────────────────────────────────

    /**
     * In-memory fake [MediaStoreGateway] that records calls and controls return values.
     * Allows testing [MediaStoreWriterImpl] lifecycle without Android ContentResolver.
     */
    private class FakeMediaStoreGateway : MediaStoreGateway {
        companion object {
            const val STUB_URI = "content://media/external/images/media/1"
        }

        var insertShouldReturnNull = false
        var openFdShouldReturnNull = false

        var lastDisplayName: String = ""
        var lastMimeType: String = ""
        var lastRelativePath: String = ""
        var fdOpened = false
        var finalised = false
        var deleted = false
        var pfdClosed = false

        // G1 leak-guard: detachFd() transfers ownership; Rust only borrows, so this must NEVER flip.
        var detachFdCalled = false

        private val mockPfd: ParcelFileDescriptor =
            mockk(relaxed = true) {
                every { fd } returns 42
                every { close() } answers { pfdClosed = true }
                every { detachFd() } answers {
                    detachFdCalled = true
                    42
                }
            }

        override fun insertPendingRow(
            displayName: String,
            mimeType: String,
            mediaCategory: MediaCategory,
            relativePath: String,
        ): String? {
            lastDisplayName = displayName
            lastMimeType = mimeType
            lastRelativePath = relativePath
            return if (insertShouldReturnNull) null else STUB_URI
        }

        override fun openWriteFd(uri: String): ParcelFileDescriptor? {
            fdOpened = true
            return if (openFdShouldReturnNull) null else mockPfd
        }

        override fun finalizePending(uri: String): Boolean {
            finalised = true
            return true
        }

        override fun deletePending(uri: String): Boolean {
            deleted = true
            return true
        }
    }
}

// Minimal stub ImportCatalog for use outside mockk relaxed mode — not needed here since
// mockCatalog uses relaxed = true. Kept as a compile-time reference.
private class NoOpImportCatalog : ImportCatalog {
    override suspend fun recordImport(
        cameraObjectHandle: Long,
        fileNameHash: String?,
        formatCategory: String,
        sizeBytes: Long?,
        mediaStoreUri: String,
        capturedAtEpochMillis: Long?,
        importedAtEpochMillis: Long,
        importSessionId: Long?,
    ) = Unit

    override suspend fun recentImports(limit: Int): List<ImportCatalogEntry> = emptyList()
}
