package dev.po4yka.frameport.core.storage.catalog.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Frameport Room database.
 *
 * Version history:
 *   1 — M09: initial schema; [ImportedMediaEntity] for local import catalog.
 *
 * All columns are redacted (no raw filenames, serials, GPS, or network identifiers).
 * See [ImportedMediaEntity] for per-column privacy documentation.
 */
@Database(
    entities = [ImportedMediaEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class FrameportDatabase : RoomDatabase() {
    abstract fun importCatalogDao(): ImportCatalogDao
}
