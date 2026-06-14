package dev.po4yka.frameport.camera.domain

import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.DiagnosticEvent
import dev.po4yka.frameport.camera.api.DiagnosticsRepository
import dev.po4yka.frameport.camera.api.ImportState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.TransferRepository
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
                                DiagnosticEvent(
                                    timestampEpochMillis = System.currentTimeMillis(),
                                    category = DiagnosticEvent.Category.Transfer,
                                    message = "Import completed for handle ${handle.value}",
                                )
                            }

                            is ImportState.Failed -> {
                                DiagnosticEvent(
                                    timestampEpochMillis = System.currentTimeMillis(),
                                    category = DiagnosticEvent.Category.Transfer,
                                    // Do not interpolate the raw error message — it may
                                    // carry filenames/paths. Category + handle only.
                                    message = "Import failed for handle ${handle.value}",
                                )
                            }

                            is ImportState.Cancelled -> {
                                DiagnosticEvent(
                                    timestampEpochMillis = System.currentTimeMillis(),
                                    category = DiagnosticEvent.Category.Transfer,
                                    message = "Import cancelled for handle ${handle.value}",
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
