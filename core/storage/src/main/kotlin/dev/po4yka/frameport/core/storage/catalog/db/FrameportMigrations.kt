package dev.po4yka.frameport.core.storage.catalog.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room schema migrations for [FrameportDatabase].
 *
 * Version history:
 *   1 — M09: initial schema; [ImportedMediaEntity].
 *   2 — M10: added [SessionProgressEntity] and [RecordedExitReasonEntity].
 *   3 — M18: added [CameraProfileEntity] table; added nullable [import_session_id] column
 *            to [imported_media] for local timeline grouping.
 *
 * DDL in each migration MUST exactly match the Room-generated schema for the target entity.
 * Column types and nullability must be consistent with the @Entity and @ColumnInfo annotations.
 */
object FrameportMigrations {
    /**
     * Migration from version 2 to version 3.
     *
     * Changes:
     * 1. Creates the camera_profile table with a unique index on serial_hash.
     * 2. Adds the nullable import_session_id INTEGER column to imported_media.
     *
     * The camera_profile DDL mirrors [CameraProfileEntity] exactly:
     * - profile_id     TEXT NOT NULL PRIMARY KEY
     * - camera_model   TEXT NOT NULL
     * - firmware_version TEXT NOT NULL
     * - serial_hash    TEXT NOT NULL  (unique index for dedup)
     * - transport_capabilities INTEGER NOT NULL
     * - compatibility_flags    INTEGER NOT NULL
     * - first_seen_epoch_ms   INTEGER NOT NULL
     * - last_seen_epoch_ms    INTEGER NOT NULL
     * - notes          TEXT            (nullable)
     *
     * The import_session_id column is nullable INTEGER (matches Long? in Kotlin).
     */
    val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create camera_profile table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS camera_profile (
                        profile_id TEXT NOT NULL PRIMARY KEY,
                        camera_model TEXT NOT NULL,
                        firmware_version TEXT NOT NULL,
                        serial_hash TEXT NOT NULL,
                        transport_capabilities INTEGER NOT NULL,
                        compatibility_flags INTEGER NOT NULL,
                        first_seen_epoch_ms INTEGER NOT NULL,
                        last_seen_epoch_ms INTEGER NOT NULL,
                        notes TEXT
                    )
                    """.trimIndent(),
                )

                // Create unique index on serial_hash for O(1) dedup lookups
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_camera_profile_serial_hash
                    ON camera_profile (serial_hash)
                    """.trimIndent(),
                )

                // Add nullable session-grouping column to imported_media
                // SQLite ALTER TABLE only supports ADD COLUMN; no DEFAULT needed for nullable INTEGER.
                db.execSQL(
                    "ALTER TABLE imported_media ADD COLUMN import_session_id INTEGER",
                )
            }
        }
}
