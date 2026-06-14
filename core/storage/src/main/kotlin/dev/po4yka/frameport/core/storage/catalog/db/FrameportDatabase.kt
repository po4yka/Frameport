package dev.po4yka.frameport.core.storage.catalog.db

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.po4yka.frameport.core.storage.session.db.ExitReasonDao
import dev.po4yka.frameport.core.storage.session.db.RecordedExitReasonEntity
import dev.po4yka.frameport.core.storage.session.db.SessionProgressDao
import dev.po4yka.frameport.core.storage.session.db.SessionProgressEntity

/**
 * Frameport Room database.
 *
 * Version history:
 *   1 — M09: initial schema; [ImportedMediaEntity] for local import catalog.
 *   2 — M10: added [SessionProgressEntity] (session transfer progress persistence across
 *            process death) and [RecordedExitReasonEntity] (idempotent LMK exit-reason
 *            deduplication). The existing destructive-migration fallback handles the upgrade.
 *
 * All columns are redacted (no raw filenames, serials, GPS, or network identifiers).
 * See per-entity KDoc for privacy documentation.
 */
@Database(
    entities = [
        ImportedMediaEntity::class,
        SessionProgressEntity::class,
        RecordedExitReasonEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class FrameportDatabase : RoomDatabase() {
    abstract fun importCatalogDao(): ImportCatalogDao

    abstract fun sessionProgressDao(): SessionProgressDao

    abstract fun exitReasonDao(): ExitReasonDao
}
