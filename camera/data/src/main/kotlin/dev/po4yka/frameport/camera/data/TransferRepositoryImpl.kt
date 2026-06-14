package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.ImportState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.TransferId
import dev.po4yka.frameport.camera.api.TransferProgress
import dev.po4yka.frameport.camera.api.TransferRepository
import dev.po4yka.frameport.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [TransferRepository].
 *
 * Maps [FujiNativeSdk.downloadObjectToFd] [TransferProgress] emissions to [ImportState]:
 * - Each progress emission -> [ImportState.Running].
 * - On completion (no more emissions) -> [ImportState.Imported] with a placeholder URI.
 * - On error -> [ImportState.Failed].
 *
 * fd ownership: [outputFd] passed to [FujiNativeSdk.downloadObjectToFd] must be an owned,
 * dup'd fd. Rust closes its copy; the Android side (MediaStore) closes its original.
 * See docs/rust/fd-ownership.md and ADR-0002.
 *
 * TODO(M09): Replace placeholder localUri with a real MediaStore content URI produced by
 *   the media import writer after the fd write completes.
 * TODO(M09): Wire real outputFd from MediaStore ParcelFileDescriptor.
 * TODO(M09): Emit a final Imported(localUri) via onCompletion once the fd-write path lands.
 */
@Singleton
class TransferRepositoryImpl
    @Inject
    constructor(
        private val fujiNativeSdk: FujiNativeSdk,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : TransferRepository {
        // NOT cancel-safe: writes to outputFd; partial writes are not rolled back on cancellation. Caller must handle partial file cleanup.
        override fun importObject(
            sessionId: SessionId,
            handle: CameraObjectHandle,
        ): Flow<ImportState> {
            // TODO(M09): Obtain a real outputFd from MediaStore (IS_PENDING=1 -> write -> IS_PENDING=0).
            val stubOutputFd = -1
            return fujiNativeSdk
                .downloadObjectToFd(sessionId, handle, stubOutputFd)
                .map<TransferProgress, ImportState> { progress -> ImportState.Running(progress) }
                .catch { throwable ->
                    // Redact: unwrap a typed FrameportException or map to a redacted
                    // Unknown — never propagate a raw native message (privacy).
                    emit(ImportState.Failed(throwable.toRedactedFrameportError()))
                }.flowOn(ioDispatcher)
        }

        // cancel-safe: single delegated suspend call; idempotent if transferId is unknown.
        override suspend fun cancelImport(transferId: TransferId) {
            withContext(ioDispatcher) { fujiNativeSdk.cancelTransfer(transferId) }
        }
    }
