package dev.po4yka.frameport.camera.media

import android.os.ParcelFileDescriptor
import dev.po4yka.frameport.camera.api.CameraMediaFormat
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.ImportState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.common.di.IoDispatcher
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.storage.catalog.ImportCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [MediaStoreWriter].
 *
 * Orchestrates the full MediaStore import lifecycle per ADR-0004 steps 4–8:
 *
 *   1. Generate a safe, deterministic display name via [FilenameValidator.generateSafeName].
 *   2. Insert a pending MediaStore row (IS_PENDING=1) via [MediaStoreGateway.insertPendingRow].
 *   3. Open a write [android.os.ParcelFileDescriptor] via [MediaStoreGateway.openWriteFd].
 *   4. Pass `pfd.fd` (the raw int) to [FujiNativeSdk.downloadObjectToFd].
 *
 * fd ownership (G1 — CRITICAL — see docs/rust/fd-ownership.md):
 *   The pfd returned by [MediaStoreGateway.openWriteFd] is ANDROID-OWNED for the entire
 *   transfer. `pfd.fd` is passed to Rust as a BORROWED fd: Rust dups it internally and
 *   closes only its own dup. Android closes the original pfd in every exit path.
 *   [android.os.ParcelFileDescriptor.detachFd] is NEVER called — doing so would transfer
 *   ownership to a Rust side that only borrows, producing a guaranteed fd leak per import.
 *
 * Cancellation contract:
 *   While the transfer is in flight, a [kotlinx.coroutines.CancellationException] thrown at
 *   any suspension point (emit, collect) is caught by the try/finally in Phase A. The finally
 *   block runs under [NonCancellable] so that cleanup (pfd close + deletePending) is guaranteed
 *   even when the calling scope is already cancelled. Cancellation propagates after cleanup —
 *   it is NOT emitted as [ImportState]. [ImportState.Cancelled] is reserved for the explicit
 *   cancel-signal path (TransferRepository.cancelImport -> FujiNativeSdk.cancelTransfer),
 *   which is wired in a later milestone.
 *
 * Privacy invariants:
 *   - Display name: "FRP_<handle>.<ext>" — no raw camera filename.
 *   - RELATIVE_PATH: "Pictures/Frameport/<yyyy-MM-dd>" or "Movies/Frameport/<yyyy-MM-dd>" — no serial.
 *   - All [FrameportError] messages are fixed, category-only strings — no uri, path, filename, or
 *     exception class name.
 *   - [ImportCatalog.recordImport] receives only the opaque handle, hash, category, and size.
 */
@Singleton
class MediaStoreWriterImpl
    @Inject
    constructor(
        private val fujiNativeSdk: FujiNativeSdk,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val importCatalog: ImportCatalog,
        private val formatMapper: FujiFormatMimeMapper,
        private val gateway: MediaStoreGateway,
    ) : MediaStoreWriter {
        // NOT cancel-safe: writes to outputFd; partial writes are not rolled back. The pending row
        // is deleted on cancellation/failure via try/finally + NonCancellable, but bytes already
        // written to the fd are not recoverable.
        override fun importToMediaStore(
            sessionId: SessionId,
            handle: CameraObjectHandle,
            format: CameraMediaFormat,
        ): Flow<ImportState> =
            flow {
                val descriptor = formatMapper.descriptorFor(format)
                val displayName = FilenameValidator.generateSafeName(handle, descriptor.fileExtension)
                val relativePath = buildRelativePath(descriptor.mediaCategory)

                // Step 4 (ADR-0004): Insert pending MediaStore row.
                val uri: String =
                    gateway.insertPendingRow(
                        displayName = displayName,
                        mimeType = descriptor.mimeType,
                        mediaCategory = descriptor.mediaCategory,
                        relativePath = relativePath,
                    ) ?: run {
                        emit(ImportState.Failed(FrameportError.Unknown("MediaStore pending-item create failed")))
                        return@flow
                    }

                // Step 5 (ADR-0004): Open write ParcelFileDescriptor for the pending row.
                // Ownership: pfd is ANDROID-OWNED and is closed by Android in every path below.
                val pfd =
                    gateway.openWriteFd(uri) ?: run {
                        // deletePending before emit: the emit() suspension point must not be reached
                        // while the pending row is still live.
                        withContext(NonCancellable) { gateway.deletePending(uri) }
                        emit(ImportState.Failed(FrameportError.Unknown("MediaStore fd open failed")))
                        return@flow
                    }

                // transferSucceeded tracks whether Phase A completed without error so the finally
                // block knows whether to delete the still-pending row. It is set to true only after
                // collect() returns normally; any exception or cancellation leaves it false.
                var transferSucceeded = false
                // transferError holds a non-cancellation Throwable so we can emit Failed after the
                // finally block runs (emitting inside finally is not safe in a cancelled scope).
                var transferError: Throwable? = null

                // Phase A — streaming transfer. The finally block runs under NonCancellable so
                // cleanup (pfd close + pending-row deletion) is guaranteed even when the calling
                // scope is already cancelled. This try MUST NOT include the post-success finalize
                // in Phase B: a cancellation after finalize would wrongly delete the committed row.
                try {
                    // Step 6 (ADR-0004): Pass pfd.fd to Rust as a BORROWED fd.
                    //
                    // Ownership: pfd.fd is the raw int of an Android-owned ParcelFileDescriptor.
                    // Rust (fuji-ffi native_download_object_to_fd) BORROWS this fd: it dups
                    // internally and closes only its own dup. Android retains and closes the
                    // original pfd in this method. detachFd() MUST NOT be called — see G1 and
                    // docs/rust/fd-ownership.md.
                    fujiNativeSdk
                        .downloadObjectToFd(sessionId, handle, pfd.fd)
                        .collect { progress ->
                            // Step 7 (ADR-0004): emit Running per progress event. emit() is also
                            // the cooperative cancellation point: it throws CancellationException
                            // if the scope is cancelled, which the finally block handles.
                            emit(ImportState.Running(progress))
                        }
                    transferSucceeded = true
                } catch (cancel: CancellationException) {
                    // Re-throw immediately; the finally block below performs cleanup under
                    // NonCancellable so the coroutine machinery propagates the cancellation.
                    throw cancel
                } catch (t: Throwable) {
                    // Ordinary transfer failure: record it so we can emit Failed after finally.
                    transferError = t
                } finally {
                    // Runs under NonCancellable so cleanup is guaranteed even in a cancelled scope.
                    // On success (transferSucceeded == true) pfd is closed and the pending row is
                    // left intact for Phase B. On failure/cancellation the pending row is deleted.
                    withContext(NonCancellable) {
                        closeSilently(pfd)
                        if (!transferSucceeded) {
                            gateway.deletePending(uri)
                        }
                    }
                }

                // Emit failure for ordinary (non-cancellation) transfer errors after cleanup.
                if (transferError != null) {
                    emit(ImportState.Failed(FrameportError.Unknown("Transfer failed")))
                    return@flow
                }

                // Phase B — success finalization. pfd is already closed by the finally block above.
                // MediaStore requires the write fd to be closed before IS_PENDING transitions to 0.

                // Step 8a (ADR-0004): finalize the pending item.
                if (!gateway.finalizePending(uri)) {
                    withContext(NonCancellable) { gateway.deletePending(uri) }
                    emit(ImportState.Failed(FrameportError.Unknown("MediaStore finalize failed")))
                    return@flow
                }

                // Record in the local import catalog (all fields are redacted). Catalog failure is
                // non-fatal — the import already succeeded and the file is visible in MediaStore.
                runCatching {
                    importCatalog.recordImport(
                        cameraObjectHandle = handle.value,
                        fileNameHash = null, // fileNameHash from CameraMediaObject is not threaded to this layer yet.
                        formatCategory = descriptor.fileExtension,
                        sizeBytes = null, // Size is unavailable from the stub transfer; real size lands in a later milestone.
                        mediaStoreUri = uri,
                        capturedAtEpochMillis = null,
                        importedAtEpochMillis = Instant.now().toEpochMilli(),
                        importSessionId = sessionId.value,
                    )
                }

                emit(ImportState.Imported(localUri = uri))
            }.flowOn(ioDispatcher)

        // ─── Helpers ─────────────────────────────────────────────────────────────

        /** Closes [pfd] silently; any IOException is swallowed (best-effort, idempotent cleanup). */
        private fun closeSilently(pfd: ParcelFileDescriptor) {
            runCatching { pfd.close() }
        }

        /**
         * Builds the MediaStore RELATIVE_PATH for the given [MediaCategory].
         *
         * Pattern: "<root>/Frameport/<yyyy-MM-dd>"
         * Privacy: encodes only the app label and current date — no camera serial, IP, or user data.
         */
        private fun buildRelativePath(mediaCategory: MediaCategory): String {
            val today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val root =
                when (mediaCategory) {
                    MediaCategory.Video -> "Movies"

                    MediaCategory.Image,
                    MediaCategory.Unknown,
                    -> "Pictures"
                }
            return "$root/Frameport/$today"
        }
    }
