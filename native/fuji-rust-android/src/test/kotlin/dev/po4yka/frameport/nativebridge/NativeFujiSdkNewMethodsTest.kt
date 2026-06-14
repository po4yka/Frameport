package dev.po4yka.frameport.nativebridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only (device-free) tests for the six new NativeFujiSdk methods introduced
 * in the M06 JNI bridge milestone:
 *   openWifiSession, listMedia, getThumbnail, downloadObjectToFd, cancelTransfer,
 *   and closeSession extended to cover wifi sessions.
 *
 * Test doubles:
 *  - [NoOpNativeFujiSdk] — used for positive/stub paths where all operations succeed.
 *  - [FakeNativeFujiSdk] — hand-rolled fake that enforces the JNI error-model contract:
 *      * commandFd < 0  -> Result.failure (mirrors ERR_INVALID_SESSION sentinel path)
 *      * unknown session id -> Result.failure (mirrors ERR_INVALID_SESSION)
 *      * unknown transferId -> Result.failure (mirrors ERR_INVALID_SESSION)
 *      * valid session -> success with empty ByteArray stubs
 *
 * No .so is loaded; no Android device is required. All tests run on the JVM via
 * ./gradlew :native:fuji-rust-android:test.
 */
class NativeFujiSdkNewMethodsTest {
    // -----------------------------------------------------------------------
    // NoOpNativeFujiSdk — positive / stub paths
    // -----------------------------------------------------------------------

    @Test
    fun noOpOpenWifiSession_validFd_returnsNoOpSession() {
        val sdk: NativeFujiSdk = NoOpNativeFujiSdk()
        val result = sdk.openWifiSession(commandFd = 3, endpointMetadata = "{}")
        assertTrue(result.isSuccess)
        val session = result.getOrThrow()
        assertTrue("NoOp impl must return the canonical NoOp session", session.isNoOp)
    }

    @Test
    fun noOpListMedia_returnsSuccessWithEmptyBytes() {
        val sdk: NativeFujiSdk = NoOpNativeFujiSdk()
        val session = NativeCameraSession.NoOp
        val result = sdk.listMedia(session)
        assertTrue(result.isSuccess)
        assertArrayEquals(ByteArray(0), result.getOrThrow())
    }

    @Test
    fun noOpGetThumbnail_returnsSuccessWithEmptyBytes() {
        val sdk: NativeFujiSdk = NoOpNativeFujiSdk()
        val result = sdk.getThumbnail(NativeCameraSession.NoOp, objectHandle = 1L)
        assertTrue(result.isSuccess)
        assertArrayEquals(ByteArray(0), result.getOrThrow())
    }

    @Test
    fun noOpDownloadObjectToFd_returnsSuccess() {
        val sdk: NativeFujiSdk = NoOpNativeFujiSdk()
        val result = sdk.downloadObjectToFd(NativeCameraSession.NoOp, objectHandle = 1L, outputFd = 5)
        assertTrue(result.isSuccess)
    }

    @Test
    fun noOpCancelTransfer_returnsSuccess() {
        val sdk: NativeFujiSdk = NoOpNativeFujiSdk()
        assertTrue(sdk.cancelTransfer(transferId = 42L).isSuccess)
    }

    @Test
    fun noOpCloseSession_wifiSession_returnsSuccess() {
        val sdk: NativeFujiSdk = NoOpNativeFujiSdk()
        val wifiSession = NativeCameraSession(id = 7L, cameraId = null, isNoOp = false)
        assertTrue(sdk.closeSession(wifiSession).isSuccess)
    }

    // -----------------------------------------------------------------------
    // FakeNativeFujiSdk — JNI error-model contract paths
    // -----------------------------------------------------------------------

    @Test
    fun fakeOpenWifiSession_validFd_returnsSessionWithPositiveId() {
        val sdk: NativeFujiSdk = FakeNativeFujiSdk()
        val result = sdk.openWifiSession(commandFd = 4, endpointMetadata = """{"ip":"192.168.0.1","port":15740}""")
        assertTrue(result.isSuccess)
        val session = result.getOrThrow()
        assertTrue(
            "openWifiSession must return a NativeCameraSession with positive id (mirrors JNI sessionId > 0 check)",
            session.id > 0L,
        )
        assertFalse("wifi session must not be flagged isNoOp", session.isNoOp)
    }

    @Test
    fun fakeOpenWifiSession_invalidFd_returnsFailure() {
        val sdk: NativeFujiSdk = FakeNativeFujiSdk()
        // commandFd = -1 mirrors the Rust ERR_INVALID_SESSION sentinel path
        val result = sdk.openWifiSession(commandFd = -1, endpointMetadata = "{}")
        assertFalse(
            "openWifiSession with invalid fd must return Result.failure (mirrors ERR_INVALID_SESSION)",
            result.isSuccess,
        )
        val ex = result.exceptionOrNull()
        assertNotNull("failure must carry an exception", ex)
        assertTrue(
            "exception must be IllegalStateException or NativeException; was ${ex!!::class.simpleName}",
            ex is IllegalStateException || ex is NativeException,
        )
    }

    @Test
    fun fakeCloseSession_validWifiSession_returnsSuccess() {
        val sdk: NativeFujiSdk = FakeNativeFujiSdk()
        // Open a wifi session first, then close it
        val openResult = sdk.openWifiSession(commandFd = 4, endpointMetadata = "{}")
        assertTrue(openResult.isSuccess)
        val session = openResult.getOrThrow()
        assertTrue(sdk.closeSession(session).isSuccess)
    }

    @Test
    fun fakeCloseSession_unknownSessionId_returnsFailure() {
        val sdk: NativeFujiSdk = FakeNativeFujiSdk()
        val unknownSession = NativeCameraSession(id = 9999L, cameraId = null, isNoOp = false)
        val result = sdk.closeSession(unknownSession)
        assertFalse(
            "closeSession with unknown id must return Result.failure (mirrors ERR_INVALID_SESSION)",
            result.isSuccess,
        )
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun fakeListMedia_validSession_returnsSuccessWithEmptyBytes() {
        val sdk: NativeFujiSdk = FakeNativeFujiSdk()
        val session = sdk.openWifiSession(commandFd = 4, endpointMetadata = "{}").getOrThrow()
        val result = sdk.listMedia(session)
        assertTrue(result.isSuccess)
        // Stub returns empty serialized payload — non-null ByteArray
        assertNotNull(result.getOrThrow())
    }

    @Test
    fun fakeListMedia_unknownSession_returnsFailure() {
        val sdk: NativeFujiSdk = FakeNativeFujiSdk()
        val unknownSession = NativeCameraSession(id = 9999L, cameraId = null, isNoOp = false)
        val result = sdk.listMedia(unknownSession)
        assertFalse(
            "listMedia with unknown session must return Result.failure (mirrors NativeException + null jbyteArray path)",
            result.isSuccess,
        )
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun fakeGetThumbnail_validSession_returnsSuccessWithEmptyBytes() {
        val sdk: NativeFujiSdk = FakeNativeFujiSdk()
        val session = sdk.openWifiSession(commandFd = 4, endpointMetadata = "{}").getOrThrow()
        val result = sdk.getThumbnail(session, objectHandle = 100L)
        assertTrue(result.isSuccess)
        assertNotNull(result.getOrThrow())
    }

    @Test
    fun fakeGetThumbnail_unknownSession_returnsFailure() {
        val sdk: NativeFujiSdk = FakeNativeFujiSdk()
        val unknownSession = NativeCameraSession(id = 9999L, cameraId = null, isNoOp = false)
        val result = sdk.getThumbnail(unknownSession, objectHandle = 100L)
        assertFalse(
            "getThumbnail with unknown session must return Result.failure (mirrors NativeException + null jbyteArray path)",
            result.isSuccess,
        )
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun fakeDownloadObjectToFd_unknownSession_returnsFailure() {
        val sdk: NativeFujiSdk = FakeNativeFujiSdk()
        val unknownSession = NativeCameraSession(id = 9999L, cameraId = null, isNoOp = false)
        val result = sdk.downloadObjectToFd(unknownSession, objectHandle = 1L, outputFd = 5)
        assertFalse(
            "downloadObjectToFd with unknown session must return Result.failure (mirrors ERR_INVALID_SESSION sentinel)",
            result.isSuccess,
        )
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun fakeDownloadObjectToFd_validSession_returnsSuccess() {
        val sdk: NativeFujiSdk = FakeNativeFujiSdk()
        val session = sdk.openWifiSession(commandFd = 4, endpointMetadata = "{}").getOrThrow()
        // Stub: dup is dropped immediately; outputFd ownership stays with Android caller.
        val result = sdk.downloadObjectToFd(session, objectHandle = 1L, outputFd = 5)
        assertTrue(result.isSuccess)
    }

    @Test
    fun fakeCancelTransfer_unknownTransferId_returnsFailure() {
        val sdk: NativeFujiSdk = FakeNativeFujiSdk()
        val result = sdk.cancelTransfer(transferId = 8888L)
        assertFalse(
            "cancelTransfer with unknown transferId must return Result.failure (mirrors ERR_INVALID_SESSION sentinel)",
            result.isSuccess,
        )
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun fakeSessionLifecycle_openThenCloseThenUse_returnsFailureAfterClose() {
        val sdk: NativeFujiSdk = FakeNativeFujiSdk()
        val session = sdk.openWifiSession(commandFd = 4, endpointMetadata = "{}").getOrThrow()
        assertTrue(sdk.closeSession(session).isSuccess)
        // After close, the session id is no longer in the registry
        assertFalse(sdk.listMedia(session).isSuccess)
    }

    @Test
    fun fakeNativeException_errorCode_matchesSentinelConstant() {
        val ex = NativeException("test panic")
        assertEquals(
            "NativeException default errorCode must match ERR_NATIVE (-100)",
            NativeException.ERR_NATIVE,
            ex.errorCode,
        )
    }

    // -----------------------------------------------------------------------
    // Integration: JniNativeFujiSdk with missing library falls back to NoOp
    // — existing contract, verified here for the new methods as well
    // -----------------------------------------------------------------------

    @Test
    fun jniSdk_missingLibrary_openWifiSession_fallsBackToNoOp() {
        val sdk =
            JniNativeFujiSdk(
                loader =
                    NativeLibraryLoader("missing_frameport_test") {
                        throw UnsatisfiedLinkError("missing_frameport_test")
                    },
            )
        // Library not loaded -> delegates to NoOpNativeFujiSdk fallback
        val result = sdk.openWifiSession(commandFd = 3, endpointMetadata = "{}")
        assertTrue(
            "JniNativeFujiSdk must fall back to NoOp when library is missing",
            result.isSuccess,
        )
    }

    @Test
    fun jniSdk_missingLibrary_listMedia_fallsBackToNoOp() {
        val sdk =
            JniNativeFujiSdk(
                loader =
                    NativeLibraryLoader("missing_frameport_test") {
                        throw UnsatisfiedLinkError("missing_frameport_test")
                    },
            )
        val result = sdk.listMedia(NativeCameraSession.NoOp)
        assertTrue(result.isSuccess)
        assertArrayEquals(ByteArray(0), result.getOrThrow())
    }

    @Test
    fun jniSdk_missingLibrary_getThumbnail_fallsBackToNoOp() {
        val sdk =
            JniNativeFujiSdk(
                loader =
                    NativeLibraryLoader("missing_frameport_test") {
                        throw UnsatisfiedLinkError("missing_frameport_test")
                    },
            )
        val result = sdk.getThumbnail(NativeCameraSession.NoOp, objectHandle = 1L)
        assertTrue(result.isSuccess)
        assertArrayEquals(ByteArray(0), result.getOrThrow())
    }

    @Test
    fun jniSdk_missingLibrary_downloadObjectToFd_fallsBackToNoOp() {
        val sdk =
            JniNativeFujiSdk(
                loader =
                    NativeLibraryLoader("missing_frameport_test") {
                        throw UnsatisfiedLinkError("missing_frameport_test")
                    },
            )
        val result = sdk.downloadObjectToFd(NativeCameraSession.NoOp, objectHandle = 1L, outputFd = 5)
        assertTrue(result.isSuccess)
    }

    @Test
    fun jniSdk_missingLibrary_cancelTransfer_fallsBackToNoOp() {
        val sdk =
            JniNativeFujiSdk(
                loader =
                    NativeLibraryLoader("missing_frameport_test") {
                        throw UnsatisfiedLinkError("missing_frameport_test")
                    },
            )
        assertTrue(sdk.cancelTransfer(transferId = 1L).isSuccess)
    }
}

/**
 * Hand-rolled fake that enforces the JNI error-model contract without loading
 * any native library. Mirrors what the real Rust implementation returns:
 *
 * - commandFd < 0  -> Result.failure(IllegalStateException) (ERR_INVALID_SESSION path)
 * - Unknown session / transfer id -> Result.failure(IllegalStateException)
 * - Valid inputs -> Result.success with empty-ByteArray stubs
 *
 * Not thread-safe by design — these are single-threaded unit tests.
 */
private class FakeNativeFujiSdk : NativeFujiSdk {
    override val diagnosticState: NativeFujiDiagnosticState =
        NativeFujiDiagnosticState(
            isNativeLibraryLoaded = false,
            message = "FakeNativeFujiSdk in use (test double).",
        )

    private var nextSessionId: Long = 1L
    private val activeSessions: MutableSet<Long> = mutableSetOf()

    override fun version(): String = "0.0.0-fake"

    override fun initialize(): Result<Unit> = Result.success(Unit)

    override fun shutdown(): Result<Unit> {
        activeSessions.clear()
        return Result.success(Unit)
    }

    override fun openNoopSession(): Result<NativeCameraSession> = Result.success(NativeCameraSession.NoOp)

    override fun openWifiSession(
        commandFd: Int,
        endpointMetadata: String,
    ): Result<NativeCameraSession> {
        // Mirror Rust: commandFd < 0 -> return ERR_INVALID_SESSION sentinel, decoded as failure
        if (commandFd < 0) {
            return Result.failure(
                IllegalStateException("native_open_wifi_session failed with code ${NativeFujiJni.ERR_INVALID_SESSION}"),
            )
        }
        val id = nextSessionId++
        activeSessions.add(id)
        return Result.success(
            NativeCameraSession(id = id, cameraId = null, isNoOp = false),
        )
    }

    override fun closeSession(session: NativeCameraSession): Result<Unit> {
        if (session.isNoOp) return Result.success(Unit)
        if (!activeSessions.remove(session.id)) {
            return Result.failure(
                IllegalStateException("native_close_session failed with code ${NativeFujiJni.ERR_INVALID_SESSION}"),
            )
        }
        return Result.success(Unit)
    }

    override fun listMedia(session: NativeCameraSession): Result<ByteArray> {
        if (!session.isNoOp && !activeSessions.contains(session.id)) {
            // jbyteArray path: unknown session -> NativeException (mirrors throw + null_mut())
            return Result.failure(
                NativeException("native:invalid-session — listMedia session=${session.id} not found"),
            )
        }
        return Result.success(ByteArray(0))
    }

    override fun getThumbnail(
        session: NativeCameraSession,
        objectHandle: Long,
    ): Result<ByteArray> {
        if (!session.isNoOp && !activeSessions.contains(session.id)) {
            return Result.failure(
                NativeException("native:invalid-session — getThumbnail session=${session.id} not found"),
            )
        }
        return Result.success(ByteArray(0))
    }

    override fun downloadObjectToFd(
        session: NativeCameraSession,
        objectHandle: Long,
        outputFd: Int,
    ): Result<Unit> {
        if (!session.isNoOp && !activeSessions.contains(session.id)) {
            // jint path: unknown session -> ERR_INVALID_SESSION integer sentinel -> failure
            return Result.failure(
                IllegalStateException(
                    "native_download_object_to_fd failed with code ${NativeFujiJni.ERR_INVALID_SESSION}",
                ),
            )
        }
        // Stub: Rust dups output_fd then drops its dup. Android caller owns and closes original.
        return Result.success(Unit)
    }

    override fun cancelTransfer(transferId: Long): Result<Unit> {
        // Transfers registry is empty in the stub (downloadObjectToFd does not register one).
        // All transfer ids are unknown -> ERR_INVALID_SESSION sentinel -> failure.
        return Result.failure(
            IllegalStateException("native_cancel_transfer failed with code ${NativeFujiJni.ERR_INVALID_SESSION}"),
        )
    }
}
