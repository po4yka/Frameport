package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.CameraMediaFormat
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.ImportState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.TransferId
import dev.po4yka.frameport.camera.api.TransferRepository
import dev.po4yka.frameport.camera.media.MediaStoreWriter
import dev.po4yka.frameport.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [TransferRepository].
 *
 * Delegates the full import lifecycle — MediaStore row creation, fd hand-off to Rust,
 * progress collection, finalize/cleanup, and ImportCatalog recording — to [MediaStoreWriter].
 *
 * Format: the [TransferRepository] interface only carries [SessionId] and [CameraObjectHandle];
 * the media format is not yet threaded through from the UI selection layer. [CameraMediaFormat.Unknown]
 * is passed for now.
 * TODO: thread the real [CameraMediaFormat] once the import UI selects a [CameraMediaObject]
 *   and passes its format through [TransferRepository.importObject] (requires interface change in M10).
 *
 * fd ownership: [MediaStoreWriter] owns the ParcelFileDescriptor lifecycle. Rust borrows the
 * raw fd integer and closes only its own dup; Android closes the original pfd after transfer.
 * See docs/rust/fd-ownership.md and ADR-0002.
 */
@Singleton
class TransferRepositoryImpl
    @Inject
    constructor(
        private val fujiNativeSdk: FujiNativeSdk,
        private val mediaStoreWriter: MediaStoreWriter,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : TransferRepository {
        // NOT cancel-safe: writes to outputFd via MediaStoreWriter; partial writes are not rolled back.
        // MediaStoreWriter deletes the pending row on cancellation (best-effort cleanup).
        override fun importObject(
            sessionId: SessionId,
            handle: CameraObjectHandle,
        ): Flow<ImportState> =
            // TODO: thread real CameraMediaFormat once the import UI passes it through.
            mediaStoreWriter.importToMediaStore(
                sessionId = sessionId,
                handle = handle,
                format = CameraMediaFormat.Unknown,
            )

        // cancel-safe: single delegated suspend call; idempotent if transferId is unknown.
        override suspend fun cancelImport(transferId: TransferId) {
            withContext(ioDispatcher) { fujiNativeSdk.cancelTransfer(transferId) }
        }
    }
