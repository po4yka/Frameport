package dev.po4yka.frameport.core.testing

import dev.po4yka.frameport.camera.api.CameraMediaFormat
import dev.po4yka.frameport.camera.api.CameraMediaObject
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.DiagnosticEvent
import dev.po4yka.frameport.camera.api.DiagnosticsRepository
import dev.po4yka.frameport.camera.api.EndpointMetadata
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.ImportState
import dev.po4yka.frameport.camera.api.MediaRepository
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.ShutterAction
import dev.po4yka.frameport.camera.api.TransferId
import dev.po4yka.frameport.camera.api.TransferProgress
import dev.po4yka.frameport.camera.api.TransferRepository
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.model.FrameportErrorException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

// ─── FakeFujiNativeSdk ────────────────────────────────────────────────────────

/**
 * Test double for [FujiNativeSdk].
 *
 * Control API:
 * - [setSession] — next [openWifiSession] returns the given [SessionId].
 * - [failOpen] — next [openWifiSession] returns failure with the given [FrameportError].
 * - [setMedia] — [listMedia] returns the given list.
 * - [failMedia] — [listMedia] returns failure with the given [FrameportError].
 * - [setThumbnail] — [getThumbnail] returns the given bytes.
 * - [emitProgress] — [downloadObjectToFd] emits the given sequence of [TransferProgress].
 */
class FakeFujiNativeSdk : FujiNativeSdk {
    private var nextSessionId: SessionId = SessionId(42L)
    private var openError: FrameportError? = null
    private var mediaList: List<CameraMediaObject> = emptyList()
    private var mediaError: FrameportError? = null
    private var thumbnailBytes: ByteArray = byteArrayOf()
    private var progressSequence: List<TransferProgress> = emptyList()

    /** Frames emitted by [liveViewFrames]. Push frames via [emitLiveViewFrame]. */
    private val _liveViewFrames =
        MutableSharedFlow<ByteArray>(
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val closedSessions = mutableListOf<SessionId>()
    val cancelledTransfers = mutableListOf<TransferId>()

    fun setSession(sessionId: SessionId) {
        nextSessionId = sessionId
    }

    fun failOpen(error: FrameportError) {
        openError = error
    }

    fun setMedia(media: List<CameraMediaObject>) {
        mediaList = media
    }

    fun failMedia(error: FrameportError) {
        mediaError = error
    }

    fun setThumbnail(bytes: ByteArray) {
        thumbnailBytes = bytes
    }

    fun emitProgress(vararg progress: TransferProgress) {
        progressSequence = progress.toList()
    }

    /**
     * Push a JPEG frame into the fake live-view stream. Latest-frame-wins; excess frames are dropped.
     * Call from tests to drive the [liveViewFrames] flow.
     */
    fun emitLiveViewFrame(jpeg: ByteArray) {
        _liveViewFrames.tryEmit(jpeg)
    }

    // cancel-safe: no real suspension; returns immediately.
    override suspend fun openWifiSession(
        socketFd: Int,
        endpointMetadata: EndpointMetadata,
    ): Result<SessionId> {
        val err = openError
        if (err != null) {
            openError = null
            return Result.failure(FrameportErrorException(err))
        }
        return Result.success(nextSessionId)
    }

    // cancel-safe: no real suspension; records call.
    override suspend fun closeSession(sessionId: SessionId) {
        closedSessions.add(sessionId)
    }

    // cancel-safe: no real suspension; returns armed list.
    override suspend fun listMedia(sessionId: SessionId): Result<List<CameraMediaObject>> {
        val err = mediaError
        if (err != null) {
            mediaError = null
            return Result.failure(FrameportErrorException(err))
        }
        return Result.success(mediaList)
    }

    // cancel-safe: no real suspension; returns armed bytes.
    override suspend fun getThumbnail(
        sessionId: SessionId,
        objectHandle: CameraObjectHandle,
    ): Result<ByteArray> = Result.success(thumbnailBytes)

    // NOT cancel-safe: emits armed progress sequence synchronously; caller handles partial-write cleanup.
    override fun downloadObjectToFd(
        sessionId: SessionId,
        objectHandle: CameraObjectHandle,
        outputFd: Int,
    ): Flow<TransferProgress> = flowOf(*progressSequence.toTypedArray())

    // cancel-safe: records cancelled transfer; no suspension.
    override suspend fun cancelTransfer(transferId: TransferId) {
        cancelledTransfers.add(transferId)
    }

    // cancel-safe: no real suspension; returns stub success.
    // TODO(M16): add control API (setRemoteShutterResult) when JNI is wired.
    override suspend fun remoteShutter(
        sessionId: SessionId,
        action: ShutterAction,
    ): Result<Unit> = Result.success(Unit)

    // cancel-safe: callbackFlow/SharedFlow collection; cancellation stops collection cleanly.
    // Frames are pushed via [emitLiveViewFrame]; latest-frame-wins via DROP_OLDEST buffer.
    override fun liveViewFrames(
        sessionId: SessionId,
        liveViewFd: Int,
    ): Flow<ByteArray> = _liveViewFrames.asSharedFlow()
}

// ─── FakeCameraRepository ─────────────────────────────────────────────────────

/**
 * Test double for [CameraRepository].
 *
 * Control API:
 * - [simulateSuccess] — [openSession] returns success with [sessionId] and state transitions to [CameraSessionState.SessionReady].
 * - [simulateFailure] — [openSession] returns failure and state transitions to [CameraSessionState.Failed].
 * - [reset] — returns state to [CameraSessionState.Idle].
 */
class FakeCameraRepository : CameraRepository {
    private val _sessionState = MutableStateFlow<CameraSessionState>(CameraSessionState.Idle)
    override val sessionState: StateFlow<CameraSessionState> = _sessionState.asStateFlow()

    private var successSessionId: SessionId? = null
    private var failureError: FrameportError? = null

    val closedSessions = mutableListOf<SessionId>()

    fun simulateSuccess(sessionId: SessionId) {
        successSessionId = sessionId
        failureError = null
    }

    fun simulateFailure(error: FrameportError) {
        failureError = error
        successSessionId = null
    }

    fun reset() {
        _sessionState.value = CameraSessionState.Idle
        successSessionId = null
        failureError = null
    }

    // cancel-safe: no real suspension; transitions state immediately.
    override suspend fun openSession(credentials: CameraWifiCredentials): Result<SessionId> {
        val err = failureError
        if (err != null) {
            _sessionState.value = CameraSessionState.Failed(err)
            return Result.failure(FrameportErrorException(err))
        }
        val id = successSessionId ?: SessionId(1L)
        _sessionState.value = CameraSessionState.SessionReady(id)
        return Result.success(id)
    }

    // cancel-safe: records closed session; no suspension.
    override suspend fun closeSession(sessionId: SessionId) {
        closedSessions.add(sessionId)
        _sessionState.value = CameraSessionState.Closed
    }
}

// ─── FakeMediaRepository ──────────────────────────────────────────────────────

/**
 * Test double for [MediaRepository].
 *
 * Control API:
 * - [setMedia] — next [listMedia] call returns the given list.
 * - [failWith] — next [listMedia] call returns failure with the given [FrameportError].
 */
class FakeMediaRepository : MediaRepository {
    private var mediaList: List<CameraMediaObject> = emptyList()
    private var error: FrameportError? = null

    fun setMedia(media: List<CameraMediaObject>) {
        mediaList = media
        error = null
    }

    fun failWith(err: FrameportError) {
        error = err
    }

    // cancel-safe: no real suspension; returns armed result immediately.
    override suspend fun listMedia(sessionId: SessionId): Result<List<CameraMediaObject>> {
        val err = error
        if (err != null) {
            error = null
            return Result.failure(FrameportErrorException(err))
        }
        return Result.success(mediaList)
    }

    // cancel-safe: no real suspension; always returns empty bytes.
    override suspend fun getThumbnail(
        sessionId: SessionId,
        handle: CameraObjectHandle,
    ): Result<ByteArray> = Result.success(byteArrayOf())
}

// ─── FakeTransferRepository ───────────────────────────────────────────────────

/**
 * Test double for [TransferRepository].
 *
 * Control API:
 * - [setImportStates] — [importObject] emits the given sequence of [ImportState].
 * Cancelled transfers are recorded in [cancelledTransfers].
 */
class FakeTransferRepository : TransferRepository {
    private var importStates: List<ImportState> =
        listOf(
            ImportState.Running(TransferProgress(TransferId(1L), 0L, 100L)),
            ImportState.Imported("content://media/fake/1"),
        )

    val cancelledTransfers = mutableListOf<TransferId>()

    fun setImportStates(vararg states: ImportState) {
        importStates = states.toList()
    }

    // NOT cancel-safe: emits scripted sequence; partial writes not rolled back on cancellation.
    override fun importObject(
        sessionId: SessionId,
        handle: CameraObjectHandle,
    ): Flow<ImportState> = flow { importStates.forEach { emit(it) } }

    // cancel-safe: records cancelled transfer; no suspension.
    override suspend fun cancelImport(transferId: TransferId) {
        cancelledTransfers.add(transferId)
    }
}

// ─── FakeDiagnosticsRepository ───────────────────────────────────────────────

/**
 * Test double for [DiagnosticsRepository].
 *
 * Recorded events are available via [recordedEvents] for assertions.
 */
class FakeDiagnosticsRepository : DiagnosticsRepository {
    private val _events = MutableSharedFlow<DiagnosticEvent>(extraBufferCapacity = 64)

    val recordedEvents = mutableListOf<DiagnosticEvent>()

    // cancel-safe: cold SharedFlow collection; cancellation stops collection cleanly.
    override fun events(): Flow<DiagnosticEvent> = _events.asSharedFlow()

    // cancel-safe: tryEmit into buffered SharedFlow; never suspends.
    override suspend fun recordEvent(event: DiagnosticEvent) {
        recordedEvents.add(event)
        _events.tryEmit(event)
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Convenience factory for [CameraMediaObject] in tests. */
fun fakeCameraMediaObject(
    handle: Long = 1L,
    format: CameraMediaFormat = CameraMediaFormat.Jpeg,
    sizeBytes: Long? = 1024L,
    captureDateUtcMillis: Long? = 0L,
    fileNameHash: String? = "abc123",
) = CameraMediaObject(
    handle = CameraObjectHandle(handle),
    format = format,
    sizeBytes = sizeBytes,
    captureDateUtcMillis = captureDateUtcMillis,
    fileNameHash = fileNameHash,
)
