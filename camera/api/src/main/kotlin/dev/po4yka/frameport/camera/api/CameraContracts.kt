package dev.po4yka.frameport.camera.api

import dev.po4yka.frameport.core.model.FrameportError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// ─── Value types ─────────────────────────────────────────────────────────────

@JvmInline
value class SessionId(
    val value: Long,
)

@JvmInline
value class CameraObjectHandle(
    val value: Long,
)

@JvmInline
value class TransferId(
    val value: Long,
)

/**
 * Camera TCP endpoint metadata passed to the Rust layer after the Android-side
 * socket has been bound to the camera network.
 * PRIVACY: host is a LAN IP — do NOT log in plain text; hash for diagnostics.
 */
data class EndpointMetadata(
    val host: String,
    val port: Int,
)

// ─── Media types ─────────────────────────────────────────────────────────────

enum class CameraMediaFormat { Jpeg, Raf, Heif, Mov, Unknown }

/**
 * Represents a media object enumerated from the camera.
 *
 * PRIVACY: [fileNameHash] is an opaque one-way hash of the original filename.
 * The raw filename must NEVER be stored, logged, or exposed across this boundary.
 */
data class CameraMediaObject(
    val handle: CameraObjectHandle,
    val format: CameraMediaFormat,
    val sizeBytes: Long?,
    val captureDateUtcMillis: Long?,
    /** Opaque SHA-256 hash of the original filename. NEVER store or expose the raw filename. */
    val fileNameHash: String?,
)

data class TransferProgress(
    val transferId: TransferId,
    val bytesTransferred: Long,
    val totalBytes: Long,
)

// ─── Session state ────────────────────────────────────────────────────────────

sealed interface CameraSessionState {
    data object Idle : CameraSessionState

    data object Connecting : CameraSessionState

    data class SessionReady(
        val sessionId: SessionId,
    ) : CameraSessionState

    data class Failed(
        val error: FrameportError,
    ) : CameraSessionState

    data object Closed : CameraSessionState
}

// ─── Import state ─────────────────────────────────────────────────────────────

sealed interface ImportState {
    data object Idle : ImportState

    data class Running(
        val progress: TransferProgress,
    ) : ImportState

    /** [localUri] is a String (not android.net.Uri) — camera:api is pure JVM. */
    data class Imported(
        val localUri: String,
    ) : ImportState

    data class Failed(
        val error: FrameportError,
    ) : ImportState

    data object Cancelled : ImportState
}

// ─── Interfaces ───────────────────────────────────────────────────────────────

/**
 * Adapter interface for the Rust native SDK, owned by :camera:data.
 *
 * fd ownership contract for [socketFd]: the caller must have dup'd the fd before passing it;
 * Rust takes ownership and closes its copy. The Android side closes its own original separately.
 *
 * fd ownership contract for [outputFd] (see [downloadObjectToFd]):
 * [outputFd] is ANDROID-OWNED and BORROWED by Rust. Rust dups the fd internally and closes only
 * its own dup. Android must NOT call detachFd() and must close the original ParcelFileDescriptor
 * after the transfer terminates (success, failure, or cancellation).
 * See docs/rust/fd-ownership.md and ADR-0002.
 */
interface FujiNativeSdk {
    /**
     * @param socketFd Owned, dup'd raw fd bound to the camera network. Rust closes this fd when done.
     * @param endpointMetadata Camera host/port metadata for the PTP-IP session.
     */
    // cancel-safe: Rust session open is a single request/response; cancellation propagates via cooperative Kotlin cancellation around the blocking call in withContext.
    suspend fun openWifiSession(
        socketFd: Int,
        endpointMetadata: EndpointMetadata,
    ): Result<SessionId>

    // cancel-safe: single delegated suspend call; no shared mutable state mutated after cancellation.
    suspend fun closeSession(sessionId: SessionId)

    // cancel-safe: single withContext call; list result is atomic.
    suspend fun listMedia(sessionId: SessionId): Result<List<CameraMediaObject>>

    // cancel-safe: single withContext call; thumbnail bytes are fetched atomically.
    suspend fun getThumbnail(
        sessionId: SessionId,
        objectHandle: CameraObjectHandle,
    ): Result<ByteArray>

    /**
     * @param outputFd Android-owned raw fd for the output file (from a MediaStore ParcelFileDescriptor).
     *   Rust BORROWS this fd: it dups the fd internally and closes only its own dup.
     *   Android MUST NOT call detachFd() and MUST close the original ParcelFileDescriptor after
     *   the transfer terminates (success, failure, or cancellation).
     *   See docs/rust/fd-ownership.md and ADR-0002.
     */
    // NOT cancel-safe: the underlying Rust transfer writes to outputFd; partial writes are not rolled back on cancellation. Caller must handle partial file cleanup.
    fun downloadObjectToFd(
        sessionId: SessionId,
        objectHandle: CameraObjectHandle,
        outputFd: Int,
    ): Flow<TransferProgress>

    // cancel-safe: single delegated call; idempotent if transferId is unknown.
    suspend fun cancelTransfer(transferId: TransferId)
}

interface CameraRepository {
    val sessionState: StateFlow<CameraSessionState>

    // cancel-safe: openSession delegates to network + native calls each wrapped in withContext; cancellation propagates to those scopes.
    suspend fun openSession(credentials: CameraWifiCredentials): Result<SessionId>

    // cancel-safe: single delegated suspend call; cleanup is best-effort.
    suspend fun closeSession(sessionId: SessionId)
}

interface MediaRepository {
    // cancel-safe: single withContext call delegated to FujiNativeSdk.listMedia.
    suspend fun listMedia(sessionId: SessionId): Result<List<CameraMediaObject>>

    // cancel-safe: single withContext call delegated to FujiNativeSdk.getThumbnail.
    suspend fun getThumbnail(
        sessionId: SessionId,
        handle: CameraObjectHandle,
    ): Result<ByteArray>
}

interface TransferRepository {
    // NOT cancel-safe: delegates to FujiNativeSdk.downloadObjectToFd which writes to an fd; partial writes are not rolled back.
    fun importObject(
        sessionId: SessionId,
        handle: CameraObjectHandle,
    ): Flow<ImportState>

    // cancel-safe: single delegated suspend call; idempotent.
    suspend fun cancelImport(transferId: TransferId)
}

interface DiagnosticsRepository {
    // cancel-safe: cold SharedFlow collection; cancellation stops collection cleanly.
    fun events(): Flow<DiagnosticEvent>

    // cancel-safe: emit into a buffered SharedFlow; never blocks.
    suspend fun recordEvent(event: DiagnosticEvent)
}
