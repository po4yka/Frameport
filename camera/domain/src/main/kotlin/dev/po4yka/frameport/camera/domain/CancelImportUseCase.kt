package dev.po4yka.frameport.camera.domain

import dev.po4yka.frameport.camera.api.TransferId
import dev.po4yka.frameport.camera.api.TransferRepository
import javax.inject.Inject

/**
 * Cancels an in-progress media import by delegating to [TransferRepository.cancelImport].
 */
class CancelImportUseCase
    @Inject
    constructor(
        private val transferRepository: TransferRepository,
    ) {
        // cancel-safe: single delegated suspend call; idempotent if transferId is unknown.
        suspend operator fun invoke(transferId: TransferId) = transferRepository.cancelImport(transferId)
    }
