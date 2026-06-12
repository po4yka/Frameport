package dev.po4yka.frameport.camera.api

import dev.po4yka.frameport.core.model.CameraId
import dev.po4yka.frameport.core.model.CameraSummary
import dev.po4yka.frameport.core.model.ConnectionStatus
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.model.ImportStatus
import dev.po4yka.frameport.core.model.MediaObject
import dev.po4yka.frameport.core.model.MediaObjectId
import dev.po4yka.frameport.core.model.TransportKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CameraRepository {
    fun observeCameras(): Flow<List<CameraSummary>>

    suspend fun camera(id: CameraId): Result<CameraSummary>
}

interface CameraConnectionManager {
    val state: StateFlow<CameraConnectionState>

    suspend fun connect(cameraId: CameraId, transportKind: TransportKind): Result<CameraSession>

    suspend fun disconnect()
}

interface CameraMediaRepository {
    fun observeMedia(session: CameraSession): Flow<List<MediaObject>>

    suspend fun mediaObject(session: CameraSession, mediaObjectId: MediaObjectId): Result<MediaObject>

    suspend fun importMedia(session: CameraSession, mediaObjectId: MediaObjectId): Result<ImportStatus>
}

interface CameraDiagnosticsRepository {
    fun events(): Flow<CameraDiagnosticEvent>

    suspend fun record(event: CameraDiagnosticEvent)
}

interface CameraSession {
    val cameraId: CameraId
    val transportKind: TransportKind
}

data class CameraConnectionState(
    val status: ConnectionStatus,
    val activeSession: CameraSession?,
    val lastError: FrameportError?,
)

data class CameraDiagnosticEvent(
    val timestampEpochMillis: Long,
    val category: Category,
    val message: String,
    val error: FrameportError? = null,
) {
    enum class Category {
        Permission,
        Transport,
        Protocol,
        Transfer,
        Storage,
        Native,
    }
}
