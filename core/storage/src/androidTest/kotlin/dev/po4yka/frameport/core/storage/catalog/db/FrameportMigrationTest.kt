package dev.po4yka.frameport.core.storage.catalog.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.frameport.core.storage.catalog.db.FrameportMigrations.MIGRATION_2_3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for [FrameportDatabase] MIGRATION_2_3.
 *
 * Why only 2→3: schema export starts at v3 (no 2.json exists in the repo). The v2 database
 * is created by hand via raw execSQL of the known v2 DDL so MigrationTestHelper can open it at
 * version 2, run MIGRATION_2_3, and validate the result against the exported v3 schema JSON.
 *
 * This test must run on a device or emulator (androidTest); it cannot run as a JVM unit test
 * because MigrationTestHelper uses Android SQLite via FrameworkSQLiteOpenHelperFactory.
 *
 * Structure: Arrange-Act-Assert (AAA).
 */
@RunWith(AndroidJUnit4::class)
class FrameportMigrationTest {
    companion object {
        private const val TEST_DB = "frameport-migration-test"
    }

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            FrameportDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    /**
     * Verify that MIGRATION_2_3:
     * 1. Creates the camera_profile table with a unique index on serial_hash.
     * 2. Adds the nullable import_session_id column to imported_media.
     * 3. Leaves pre-existing imported_media rows intact (data survival).
     *
     * The starting v2 database is constructed by hand because no exported 2.json exists;
     * the DDL exactly mirrors the entity annotations as of v2 (before M18 changes).
     */
    @Test
    fun migration2To3_createsProfileTable_addsSessionIdColumn_preservesExistingRows() {
        // ── Arrange: create a v2 database by hand ────────────────────────────────
        // MigrationTestHelper.createDatabase opens an SQLiteOpenHelper at the given version
        // and returns its SupportSQLiteDatabase for raw DDL / DML.
        val db = helper.createDatabase(TEST_DB, 2)

        // v2 schema DDL ── must match entities as they existed at version 2 exactly.
        // imported_media (v1, unchanged in v2)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS imported_media (
                local_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                camera_object_handle INTEGER NOT NULL,
                file_name_hash TEXT,
                format_category TEXT NOT NULL,
                size_bytes INTEGER,
                mediastore_uri TEXT NOT NULL,
                import_status TEXT NOT NULL,
                captured_at_epoch_millis INTEGER,
                imported_at_epoch_millis INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_imported_media_camera_object_handle
            ON imported_media (camera_object_handle)
            """.trimIndent(),
        )

        // session_progress (v2, added in MIGRATION_1_2)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS session_progress (
                session_id INTEGER NOT NULL PRIMARY KEY,
                object_handle INTEGER NOT NULL,
                bytes_transferred INTEGER NOT NULL,
                total_bytes INTEGER NOT NULL,
                state TEXT NOT NULL,
                updated_at_millis INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        // recorded_exit_reason (v2, added in MIGRATION_1_2)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS recorded_exit_reason (
                id TEXT NOT NULL PRIMARY KEY,
                recorded_at_millis INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        // Insert a representative imported_media row to verify data survival after migration.
        // NULL values for nullable columns; no import_session_id column exists in v2.
        db.execSQL(
            """
            INSERT INTO imported_media
                (local_id, camera_object_handle, file_name_hash, format_category, size_bytes,
                 mediastore_uri, import_status, captured_at_epoch_millis, imported_at_epoch_millis)
            VALUES
                (1, 12345, 'abc123hash', 'jpeg', 4096,
                 'content://media/external/images/media/1', 'imported', 1700000000000, 1700000001000)
            """.trimIndent(),
        )

        db.close()

        // ── Act: run MIGRATION_2_3 and validate against exported v3 schema ───────
        // validateDroppedTables=true ensures no spurious tables linger.
        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        // ── Assert ────────────────────────────────────────────────────────────────

        // 1. camera_profile table exists and accepts a row
        migratedDb.execSQL(
            """
            INSERT INTO camera_profile
                (profile_id, camera_model, firmware_version, serial_hash,
                 transport_capabilities, compatibility_flags,
                 first_seen_epoch_ms, last_seen_epoch_ms, notes)
            VALUES
                ('test-profile-id', 'X-T5', '1.00', 'sha256hashvalue',
                 1, 0,
                 1700000000000, 1700000001000, NULL)
            """.trimIndent(),
        )

        migratedDb
            .query("SELECT * FROM camera_profile WHERE profile_id = 'test-profile-id'")
            .use { cursor ->
                assertNotNull("camera_profile row must exist", cursor)
                assertEquals("expected exactly one camera_profile row", 1, cursor.count)
                cursor.moveToFirst()
                assertEquals(
                    "sha256hashvalue",
                    cursor.getString(cursor.getColumnIndexOrThrow("serial_hash")),
                )
            }

        // 2. unique index on serial_hash — a duplicate insert must fail
        var indexViolationThrown = false
        try {
            migratedDb.execSQL(
                """
                INSERT INTO camera_profile
                    (profile_id, camera_model, firmware_version, serial_hash,
                     transport_capabilities, compatibility_flags,
                     first_seen_epoch_ms, last_seen_epoch_ms, notes)
                VALUES
                    ('other-id', 'X-T4', '2.00', 'sha256hashvalue',
                     1, 0,
                     1700000002000, 1700000003000, NULL)
                """.trimIndent(),
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            indexViolationThrown = true
        }
        assertEquals(
            "Duplicate serial_hash must violate the unique index",
            true,
            indexViolationThrown,
        )

        // 3. import_session_id column now exists on imported_media
        migratedDb
            .query("SELECT import_session_id FROM imported_media WHERE local_id = 1")
            .use { cursor ->
                assertNotNull("import_session_id column must be selectable", cursor)
                assertEquals("pre-existing row must survive migration", 1, cursor.count)
                cursor.moveToFirst()
                // The column was added as nullable with no DEFAULT; the pre-existing row has NULL.
                assertEquals(
                    "pre-existing row import_session_id must be NULL",
                    true,
                    cursor.isNull(cursor.getColumnIndexOrThrow("import_session_id")),
                )
            }

        migratedDb.close()
    }
}
