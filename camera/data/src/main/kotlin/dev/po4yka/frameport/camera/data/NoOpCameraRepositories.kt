package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.CameraConnectionManager
import dev.po4yka.frameport.camera.api.CameraConnectionState
import dev.po4yka.frameport.camera.api.CameraDiagnosticEvent
import dev.po4yka.frameport.camera.api.CameraDiagnosticsRepository
import dev.po4yka.frameport.camera.api.CameraMediaRepository
import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.camera.api.CameraSession
import dev.po4yka.frameport.core.model.CameraId
import dev.po4yka.frameport.core.model.CameraSummary
import dev.po4yka.frameport.core.model.ConnectionStatus
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.model.ImportStatus
import dev.po4yka.frameport.core.model.MediaObject
import dev.po4yka.frameport.core.model.MediaObjectId
import dev.po4yka.frameport.core.model.TransportKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

class NoOpCameraRepository : CameraRepository {
    override fun observeCameras(): Flow<List<CameraSummary>> = flowOf(emptyList())

    override suspend fun camera(id: CameraId): Result<CameraSummary> = Result.failure(
        IllegalStateException("No camera repository implementation is configured."),
    )
}

class NoOpCameraConnectionManager : CameraConnectionManager {
    private val connectionState = MutableStateFlow(
        CameraConnectionState(
            status = ConnectionStatus.Disconnected,
            activeSession = null,
            lastError = null,
        ),
    )

    override val state: StateFlow<CameraConnectionState> = connectionState

    override suspend fun connect(cameraId: CameraId, transportKind: TransportKind): Result<CameraSession> =
        Result.failure(IllegalStateException("No camera connection implementation is configured."))

    override suspend fun disconnect() {
        connectionState.value = connectionState.value.copy(
            status = ConnectionStatus.Disconnected,
            activeSession = null,
            lastError = null,
        )
    }
}

class NoOpCameraMediaRepository : CameraMediaRepository {
    override fun observeMedia(session: CameraSession): Flow<List<MediaObject>> = emptyFlow()

    override suspend fun mediaObject(session: CameraSession, mediaObjectId: MediaObjectId): Result<MediaObject> =
        Result.failure(IllegalStateException("No media repository implementation is configured."))

    override suspend fun importMedia(session: CameraSession, mediaObjectId: MediaObjectId): Result<ImportStatus> =
        Result.failure(IllegalStateException("No media import implementation is configured."))
}

class NoOpCameraDiagnosticsRepository : CameraDiagnosticsRepository {
    private val diagnosticEvents = MutableSharedFlow<CameraDiagnosticEvent>(extraBufferCapacity = 64)

    override fun events(): Flow<CameraDiagnosticEvent> = diagnosticEvents

    override suspend fun record(event: CameraDiagnosticEvent) {
        diagnosticEvents.emit(event)
    }
}
