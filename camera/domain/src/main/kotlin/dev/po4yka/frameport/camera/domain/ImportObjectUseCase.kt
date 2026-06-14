package dev.po4yka.frameport.camera.domain

import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.DiagnosticsRepository
import dev.po4yka.frameport.camera.api.ErrorLayer
import dev.po4yka.frameport.camera.api.ImportState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.TransferRepository
import dev.po4yka.frameport.camera.api.defaultCategory
import dev.po4yka.frameport.camera.api.diagnosticEvent
import dev.po4yka.frameport.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Imports a single media object from the camera, recording a [DiagnosticEvent]
 * on each terminal [ImportState] ([ImportState.Imported], [ImportState.Failed],
 * [ImportState.Cancelled]).
 *
 * Delegates transfer to [TransferRepository.importObject] and diagnostics
 * recording to [DiagnosticsRepository.recordEvent]. Runs on [ioDispatcher].
 */
class ImportObjectUseCase
    @Inject
    constructor(
        private val transferRepository: TransferRepository,
        private val diagnosticsRepository: DiagnosticsRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        // cancel-safe: collects transferRepository.importObject flow; records DiagnosticEvent on each terminal state via onEach; flowOn(ioDispatcher) makes collection and emission cooperative under cancellation.
        fun invoke(
            sessionId: SessionId,
            handle: CameraObjectHandle,
        ): Flow<ImportState> =
            transferRepository
                .importObject(sessionId, handle)
                .onEach { state ->
                    val event =
                        when (state) {
                            is ImportState.Imported -> {
                                diagnosticEvent(
                                    layer = ErrorLayer.MediaTransfer,
                                    category = defaultCategory(ErrorLayer.MediaTransfer),
                                    // Category-only message — raw handle value is a numeric
                                    // object id, not a device identifier, but omitted for
                                    // uniformity with other category-only messages.
                                    message = "Import completed",
                                )
                            }

                            is ImportState.Failed -> {
                                diagnosticEvent(
                                    layer = ErrorLayer.MediaTransfer,
                                    category = defaultCategory(ErrorLayer.MediaTransfer),
                                    // Do not interpolate the raw error message — it may
                                    // carry filenames/paths. Category-only message.
                                    message = "Import failed",
                                )
                            }

                            is ImportState.Cancelled -> {
                                diagnosticEvent(
                                    layer = ErrorLayer.MediaTransfer,
                                    category = defaultCategory(ErrorLayer.MediaTransfer),
                                    message = "Import cancelled",
                                )
                            }

                            else -> {
                                null
                            }
                        }
                    if (event != null) {
                        diagnosticsRepository.recordEvent(event)
                    }
                }.flowOn(ioDispatcher)
    }
