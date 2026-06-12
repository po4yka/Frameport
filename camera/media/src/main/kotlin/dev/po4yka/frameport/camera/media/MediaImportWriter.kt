package dev.po4yka.frameport.camera.media

import dev.po4yka.frameport.core.model.ImportStatus
import dev.po4yka.frameport.core.model.MediaObject

interface MediaImportWriter {
    suspend fun prepare(mediaObject: MediaObject): Result<MediaImportTarget>

    suspend fun markComplete(target: MediaImportTarget): Result<ImportStatus>

    suspend fun markFailed(target: MediaImportTarget, reason: Throwable): Result<ImportStatus>
}

data class MediaImportTarget(
    val id: String,
    val ownedFileDescriptor: Int,
)

class NoOpMediaImportWriter : MediaImportWriter {
    override suspend fun prepare(mediaObject: MediaObject): Result<MediaImportTarget> =
        Result.failure(IllegalStateException("Media import writing is not implemented."))

    override suspend fun markComplete(target: MediaImportTarget): Result<ImportStatus> =
        Result.failure(IllegalStateException("Media import writing is not implemented."))

    override suspend fun markFailed(target: MediaImportTarget, reason: Throwable): Result<ImportStatus> =
        Result.failure(IllegalStateException("Media import writing is not implemented."))
}
