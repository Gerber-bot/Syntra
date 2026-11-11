package com.syntra.app.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.syntra.app.neural.NeuralLabReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Stage A integrity for the real Android SQLite engine, executed on a disposable
 * database inside the lab sandbox. It never opens `syntra.db` nor the product app.
 *
 * Covers the pending Android checks:
 * - populated v7 database migrated to v8 with historical/duplicate/blank session UUIDs;
 * - an existing v8 database with historical UUIDs opened without migration.
 *
 * Restore/rollback and Compose alias recomposition already have instrumented coverage in
 * BackupManagerRestoreIntegrationTest, ComposeAliasRecompositionTest and
 * ParserScreenIntegrationTest; they run unchanged in the lab variant.
 */
@RunWith(AndroidJUnit4::class)
class StageAMigrationInstrumentedTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private val helpers = mutableListOf<SupportSQLiteOpenHelper>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.cacheDir, "stage-a-lab-${System.nanoTime()}.db")
        dbFile.delete()
    }

    @After
    fun tearDown() {
        helpers.forEach { runCatching { it.close() } }
        helpers.clear()
        dbFile.delete()
    }

    private fun openDatabase(version: Int, create: (SupportSQLiteDatabase) -> Unit): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = create(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    throw IllegalStateException("Unexpected upgrade $oldVersion -> $newVersion")
                }
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        helpers += helper
        return helper.writableDatabase
    }

    private fun createV7(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE workout_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                startedAtMillis INTEGER NOT NULL,
                finishedAtMillis INTEGER,
                notes TEXT NOT NULL,
                category TEXT NOT NULL,
                sessionUuid TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX index_workout_sessions_sessionUuid ON workout_sessions (sessionUuid)")
    }

    private fun createV8(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE workout_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                startedAtMillis INTEGER NOT NULL,
                finishedAtMillis INTEGER,
                notes TEXT NOT NULL,
                category TEXT NOT NULL,
                sessionUuid TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX index_workout_sessions_sessionUuid ON workout_sessions (sessionUuid)")
    }

    private fun insertV7(
        db: SupportSQLiteDatabase,
        id: Long,
        title: String,
        startedAt: Long,
        uuid: String
    ) {
        db.execSQL(
            "INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis, notes, category, sessionUuid) " +
                "VALUES ($id, '$title', $startedAt, NULL, '', 'Piernas', '$uuid')"
        )
    }

    @Test
    fun populated_v7_database_migrates_to_v8_without_losing_historical_uuid_policy() {
        val db = openDatabase(version = 7) { createV7(it) }

        insertV7(db, 1, "Legacy blank", 1_000, "")
        insertV7(db, 2, "Legacy whitespace", 2_000, "   ")
        insertV7(db, 3, "Modern", 3_000, "uuid-modern-1")
        insertV7(db, 4, "Duplicate first", 4_000, "uuid-dup")
        insertV7(db, 5, "Duplicate second", 5_000, "uuid-dup")
        insertV7(db, 6, "Reserved collision", 6_000, "uuid-dup_dup_5")

        AppDatabase.MIGRATION_7_8.migrate(db)

        val uuids = mutableMapOf<Long, String?>()
        db.query(
            "SELECT id, title, startedAtMillis, finishedAtMillis, sessionUuid FROM workout_sessions ORDER BY id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                assertNull("Legacy finishedAt must stay NULL", if (cursor.isNull(3)) null else cursor.getLong(3))
                uuids[id] = if (cursor.isNull(4)) null else cursor.getString(4)
            }
        }

        assertEquals(6, uuids.size)
        assertNull("Blank legacy UUID must become NULL", uuids[1])
        assertNull("Whitespace legacy UUID must become NULL", uuids[2])
        assertEquals("uuid-modern-1", uuids[3])
        assertEquals("First duplicate keeps the normalized UUID", "uuid-dup", uuids[4])
        assertEquals(
            "Second duplicate must receive a collision-free substitute",
            "uuid-dup_dup_5_1",
            uuids[5]
        )
        assertEquals(
            "A reserved original UUID must not be stolen by a generated substitute",
            "uuid-dup_dup_5",
            uuids[6]
        )

        val duplicateRejected = runCatching {
            db.execSQL(
                "INSERT INTO workout_sessions (title, startedAtMillis, finishedAtMillis, notes, category, sessionUuid) " +
                    "VALUES ('dup', 7000, NULL, '', '', 'uuid-modern-1')"
            )
        }.exceptionOrNull()
        val uniqueIndexEnforced = duplicateRejected is SQLiteConstraintException ||
            duplicateRejected?.message?.contains("UNIQUE", ignoreCase = true) == true

        db.execSQL(
            "INSERT INTO workout_sessions (title, startedAtMillis, finishedAtMillis, notes, category, sessionUuid) " +
                "VALUES ('null a', 8000, NULL, '', '', NULL)"
        )
        db.execSQL(
            "INSERT INTO workout_sessions (title, startedAtMillis, finishedAtMillis, notes, category, sessionUuid) " +
                "VALUES ('null b', 9000, NULL, '', '', NULL)"
        )
        val nullRows = db.query("SELECT COUNT(*) FROM workout_sessions WHERE sessionUuid IS NULL").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

        NeuralLabReport.record(
            context,
            "stage_a_migration_7_8_populated",
            if (uniqueIndexEnforced && nullRows == 4) "PASS" else "FAIL",
            mapOf(
                "rows" to uuids.size,
                "historicalBlankNull" to (uuids[1] == null && uuids[2] == null),
                "firstDuplicateKept" to (uuids[4] == "uuid-dup"),
                "duplicateSubstitute" to uuids[5],
                "reservedCollisionPreserved" to (uuids[6] == "uuid-dup_dup_5"),
                "uniqueIndexEnforced" to uniqueIndexEnforced,
                "nullUuidRows" to nullRows
            )
        )

        assertTrue("The v8 unique index must reject duplicate UUIDs", uniqueIndexEnforced)
        assertEquals("Nullable UUIDs must allow multiple NULL rows", 4, nullRows)
    }

    @Test
    fun existing_v8_database_with_historical_uuids_opens_without_migration() {
        val v8 = openDatabase(version = 8) { createV8(it) }
        v8.execSQL(
            "INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis, notes, category, sessionUuid) " +
                "VALUES (10, 'Historical', 10000, 11000, 'nota', 'Espalda', 'uuid-historico-10')"
        )
        v8.execSQL(
            "INSERT INTO workout_sessions (id, title, startedAtMillis, finishedAtMillis, notes, category, sessionUuid) " +
                "VALUES (11, 'Legacy null', 12000, NULL, '', 'Espalda', NULL)"
        )

        // Reopen the same v8 file: no onCreate, no onUpgrade must run.
        val reopened = openDatabase(version = 8) { error("onCreate must not run for an existing v8 database") }
        val version = reopened.version

        val rows = mutableListOf<Triple<Long, String?, Long?>>()
        reopened.query(
            "SELECT id, sessionUuid, finishedAtMillis FROM workout_sessions ORDER BY id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += Triple(
                    cursor.getLong(0),
                    if (cursor.isNull(1)) null else cursor.getString(1),
                    if (cursor.isNull(2)) null else cursor.getLong(2)
                )
            }
        }

        NeuralLabReport.record(
            context,
            "stage_a_existing_v8_historical_uuids",
            if (version == 8 && rows.size == 2 && rows[0].second == "uuid-historico-10") "PASS" else "FAIL",
            mapOf(
                "schemaVersion" to version,
                "rows" to rows.size,
                "historicalUuidPreserved" to (rows.getOrNull(0)?.second == "uuid-historico-10"),
                "legacyNullPreserved" to (rows.getOrNull(1)?.second == null)
            )
        )

        assertEquals(8, version)
        assertEquals(2, rows.size)
        assertEquals(10L, rows[0].first)
        assertEquals("uuid-historico-10", rows[0].second)
        assertEquals(11_000L, rows[0].third)
        assertEquals(11L, rows[1].first)
        assertNull(rows[1].second)
        assertNull(rows[1].third)
    }
}
