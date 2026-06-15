package dev.po4yka.frameport.core.storage.catalog.db

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.po4yka.frameport.core.storage.profile.db.CameraProfileDao
import dev.po4yka.frameport.core.storage.profile.db.CameraProfileEntity
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
 *            deduplication). The existing destructive-migration fallback handled the upgrade.
 *   3 — M18: added [CameraProfileEntity] (camera body catalog with bitmask capability/flag
 *            columns); added nullable import_session_id column to [ImportedMediaEntity] for
 *            local timeline session grouping. Explicit migration replaces the destructive
 *            fallback — schema is now stable enough for a versioned migration path.
 *
 * All columns are redacted (no raw filenames, serials, GPS, or network identifiers).
 * See per-entity KDoc for privacy documentation.
 */
@Database(
    entities = [
        ImportedMediaEntity::class,
        SessionProgressEntity::class,
        RecordedExitReasonEntity::class,
        CameraProfileEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class FrameportDatabase : RoomDatabase() {
    abstract fun importCatalogDao(): ImportCatalogDao

    abstract fun sessionProgressDao(): SessionProgressDao

    abstract fun exitReasonDao(): ExitReasonDao

    abstract fun cameraProfileDao(): CameraProfileDao
}
