package dev.po4yka.frameport.core.storage.catalog

import dev.po4yka.frameport.core.storage.catalog.db.ImportCatalogDao
import dev.po4yka.frameport.core.storage.catalog.db.ImportCatalogStatus
import dev.po4yka.frameport.core.storage.catalog.db.ImportedMediaEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [ImportCatalog].
 *
 * Delegates all persistence to [ImportCatalogDao]. All string values stored here
 * must already be redacted by the caller per privacy-local-first.md.
 */
@Singleton
class RoomImportCatalog
    @Inject
    constructor(
        private val dao: ImportCatalogDao,
    ) : ImportCatalog {
        // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
        override suspend fun recordImport(
            cameraObjectHandle: Long,
            fileNameHash: String?,
            formatCategory: String,
            sizeBytes: Long?,
            mediaStoreUri: String,
            capturedAtEpochMillis: Long?,
            importedAtEpochMillis: Long,
            importSessionId: Long?,
        ) {
            dao.upsert(
                ImportedMediaEntity(
                    cameraObjectHandle = cameraObjectHandle,
                    fileNameHash = fileNameHash,
                    formatCategory = formatCategory,
                    sizeBytes = sizeBytes,
                    mediaStoreUri = mediaStoreUri,
                    importStatus = ImportCatalogStatus.IMPORTED,
                    capturedAtEpochMillis = capturedAtEpochMillis,
                    importedAtEpochMillis = importedAtEpochMillis,
                    importSessionId = importSessionId,
                ),
            )
        }

        // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
        override suspend fun recentImports(limit: Int): List<ImportCatalogEntry> =
            // Guard: SQLite treats LIMIT with a negative value as "no limit" (returns the whole
            // table). Coerce to a minimum of 1 so the DAO always receives a positive bound.
            dao.recentImports(limit.coerceAtLeast(1)).map { entity ->
                ImportCatalogEntry(
                    localId = entity.localId,
                    cameraObjectHandle = entity.cameraObjectHandle,
                    fileNameHash = entity.fileNameHash,
                    formatCategory = entity.formatCategory,
                    sizeBytes = entity.sizeBytes,
                    mediaStoreUri = entity.mediaStoreUri,
                    importStatus = entity.importStatus,
                    capturedAtEpochMillis = entity.capturedAtEpochMillis,
                    importedAtEpochMillis = entity.importedAtEpochMillis,
                    importSessionId = entity.importSessionId,
                )
            }
    }
