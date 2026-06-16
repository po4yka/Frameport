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
 * fd ownership contract for [socketFd]: the caller passes a detached socket fd to the native
 * bridge exactly once. Rust borrows and duplicates it synchronously; the Kotlin adapter closes
 * the detached fd after the bridge call returns. Rust closes only its own dup.
 *
 * fd ownership contract for [outputFd] (see [downloadObjectToFd]):
 * [outputFd] is ANDROID-OWNED and BORROWED by Rust. Rust dups the fd internally and closes only
 * its own dup. Android must NOT call detachFd() and must close the original ParcelFileDescriptor
 * after the transfer terminates (success, failure, or cancellation).
 * See docs/rust/fd-ownership.md and ADR-0002.
 */
interface FujiNativeSdk {
    /**
     * @param socketFd Detached raw fd bound to the camera network. The native bridge borrows
     *   and dups it; the Kotlin adapter closes this fd after the bridge call returns.
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

    /**
     * Open a live-view frame stream for the given session.
     *
     * Returns a cold [Flow<ByteArray>] where each emission is a complete JPEG frame
     * (SOI … EOI inclusive) parsed by the Rust zero-alloc live-view parser.
     *
     * Backpressure / latest-frame-wins contract:
     * The underlying [kotlinx.coroutines.channels.callbackFlow] uses
     * [BufferOverflow.DROP_OLDEST] so the read loop in Rust is never blocked on a
     * slow Compose consumer. Callers MUST NOT assume every frame is delivered — only
     * the most recent frame is guaranteed at any point.
     *
     * fd ownership contract:
     * [liveViewFd] is an Android-owned, detached raw socket fd already bound to the
     * camera network and connected to port 55742 (LIVEVIEW_CHANNEL_PORT,
     * master-constants.md §1). The native bridge borrows and dups it when
     * [nativeLiveViewStart] is called inside this flow; the Kotlin adapter closes this
     * detached fd after the start call returns. Rust closes only its dup when stopped.
     * Callers MUST NOT close or use this fd after passing it here.
     * See docs/rust/fd-ownership.md and ADR-0002.
     *
     * Lifecycle:
     * Collecting the flow calls [nativeLiveViewStart]; [awaitClose] calls
     * [nativeLiveViewStop]. Cancelling the collector cancels the flow and stops the
     * Rust read loop.
     *
     * @param sessionId Active PTP-IP session.
     * @param liveViewFd Detached fd for the live-view socket (port 55742).
     * @return Cold [Flow<ByteArray>] of JPEG frames. Latest-frame-wins; frames may be dropped.
     */
    // cancel-safe: callbackFlow with awaitClose; cancellation triggers awaitClose block
    // which calls nativeLiveViewStop. The Rust stop flag is set atomically; the worker
    // thread drains and exits. No shared mutable state is mutated after cancellation.
    fun liveViewFrames(
        sessionId: SessionId,
        liveViewFd: Int,
    ): Flow<ByteArray>

    /**
     * Open a USB PTP session for a camera device connected over UsbManager.
     *
     * Called by [CameraUsbConnector] after [Os.dup] on [UsbDeviceConnection.fileDescriptor].
     * Rust registers the dup'd fd in USB_SESSIONS and returns a new session id.
     *
     * fd ownership contract:
     * [usbFd] is an Android-produced dup of the [UsbDeviceConnection.fileDescriptor].
     * // OWNERSHIP: Android keeps + closes the original via UsbDeviceConnection.close().
     * //            Rust owns + closes the dup via OwnedFd on Drop in fuji-ffi.
     * [descriptors] are the raw USB interface descriptor bytes used by Rust to identify
     * bulk-IN and bulk-OUT endpoints (no alignment guarantee; Rust validates every
     * length field before indexing). Android may free the ByteArray after this call.
     *
     * @param usbFd Dup'd raw fd for the USB device. Ownership transfers to Rust.
     * @param descriptors Raw USB interface descriptor bytes for endpoint discovery.
     * @return [Result.success] with the Rust-assigned [SessionId]; [Result.failure] on error.
     *
     * cancel-safe: single withContext call wrapping the JNI bridge; no shared mutable
     * state is mutated after cancellation.
     */
    suspend fun openUsbSession(
        usbFd: Int,
        descriptors: ByteArray,
    ): Result<SessionId>

    /**
     * Close a USB PTP session previously opened via [openUsbSession].
     *
     * Rust removes the session from USB_SESSIONS and the OwnedFd is dropped,
     * closing the dup. Android closes the original [UsbDeviceConnection] separately.
     *
     * Idempotent: closing an unknown session id is a no-op.
     *
     * cancel-safe: single withContext call; no shared mutable state mutated after cancellation.
     *
     * @param sessionId The [SessionId] returned by [openUsbSession].
     */
    suspend fun closeUsbSession(sessionId: SessionId)

    /**
     * Send a remote shutter action to the camera over PTP-IP.
     *
     * Wire mapping (master-constants.md §3, docs/protocol/wifi-ptp-ip.md):
     *   HalfPress / FullPress → InitiateCapture (0x100E) via RemoteSession
     *   Release               → TerminateOpenCapture (0x1018) via RemoteSession
     *
     * JNI wiring is DEFERRED to M16: the adapter currently returns a stub success
     * or NotImplemented via [NativeFujiSdk]. The canonical RemoteSession is tested
     * in Rust via fuji-sim. TODO(M16): wire to fuji-ffi JNI entry point.
     *
     * @param sessionId Active PTP-IP session returned by [openWifiSession].
     * @param action    The shutter state to send (HalfPress, FullPress, or Release).
     * @return [Result.success] on acceptance; [Result.failure] with a typed error message on rejection.
     */
    // cancel-safe: single withContext call delegated to JNI stub; no shared mutable state mutated after cancellation.
    suspend fun remoteShutter(
        sessionId: SessionId,
        action: ShutterAction,
    ): Result<Unit>
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

// ─── LiveViewUiState ──────────────────────────────────────────────────────────

/**
 * UI state machine for the live-view screen, consumed by
 * `LiveviewViewModel` in `:feature:liveview`.
 *
 * Transitions (happy path):
 * ```
 * Idle → Connecting → Streaming(fps, dropCount) → Stopped
 *   ↘ (any error) → Error(message)
 * ```
 *
 * [Streaming.fps] is throttled to one update per second by the ViewModel.
 * [Streaming.dropCount] mirrors `FrameStats.drop_count` from the Rust parser
 * and is safe for diagnostic display (no PII).
 *
 * NOTE: [Error.message] must be a pre-redacted, PII-free string per
 * `privacy-local-first.md`. Never pass raw IP addresses, filenames, or
 * camera serial numbers as the message.
 */
sealed interface LiveViewUiState {
    /** No live-view session; initial and reset state. */
    data object Idle : LiveViewUiState

    /** Socket is bound; waiting for the Rust read loop to emit the first frame. */
    data object Connecting : LiveViewUiState

    /**
     * Frames are being received and decoded.
     *
     * @param fps Frames per second, throttled to 1 Hz update rate by the ViewModel.
     * @param dropCount Cumulative frames dropped by the Rust parser (FrameTooLarge).
     *   Privacy-safe: a counter, not a filename or device identifier.
     */
    data class Streaming(
        val fps: Float,
        val dropCount: Long,
    ) : LiveViewUiState

    /** The session was stopped cleanly by the user or because the camera disconnected. */
    data object Stopped : LiveViewUiState

    /**
     * An unrecoverable error terminated the live-view session.
     *
     * @param message Pre-redacted, PII-free error description. Must NOT contain
     *   IP addresses, filenames, MAC addresses, or camera serial numbers.
     *   Map typed [dev.po4yka.frameport.core.model.FrameportError] to a safe string
     *   before constructing this state.
     */
    data class Error(
        val message: String,
    ) : LiveViewUiState
}
