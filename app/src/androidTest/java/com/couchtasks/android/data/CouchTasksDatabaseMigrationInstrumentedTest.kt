package com.couchtasks.android.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CouchTasksDatabaseMigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CouchTasksDatabase::class.java,
    )

    @Test
    fun migration1To2_preservesExistingDataAndAddsArchivedAtColumn() {
        helper.createDatabase(TEST_DB, 1).apply {
            insertVersion1Project()
            insertVersion1Task()
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            *CouchTasksDatabase.migrations(),
        )

        migrated.query("SELECT name, archived_at FROM projects WHERE uuid = 'project-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Inbox", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        migrated.query("SELECT title, project_uuid FROM tasks WHERE uuid = 'task-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Read", cursor.getString(0))
            assertEquals("project-1", cursor.getString(1))
        }
        migrated.close()
    }

    @Test
    fun migration2To3_preservesDataAndCreatesDurableSingletonIssue() {
        helper.createDatabase(TEST_DB_V3, 2).apply {
            execSQL(
                """
                INSERT INTO projects(
                    uuid, name, name_normalized, created_at, updated_at, archived_at, sync_revision, sync_dirty
                ) VALUES (
                    'project-1', 'Inbox', 'inbox', '2026-04-27T10:03:00Z', '2026-04-27T10:03:00Z', null, 0, 1
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB_V3, 3, true, *CouchTasksDatabase.migrations())
        migrated.execSQL(
            """
            INSERT INTO sync_issue(
                id, kind, http_status, detail, request_checkpoint, entity_uuid, related_uuid,
                server_checkpoint, detected_at
            ) VALUES (1, 'UNKNOWN_CONFLICT', 409, 'conflict', 7, null, null, null, '2026-09-04T10:00:00Z')
            """.trimIndent(),
        )
        migrated.query("SELECT kind, detail FROM sync_issue WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("UNKNOWN_CONFLICT", cursor.getString(0))
            assertEquals("conflict", cursor.getString(1))
        }
        migrated.query("SELECT name FROM projects WHERE uuid = 'project-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Inbox", cursor.getString(0))
        }
        migrated.close()
    }

    @Test
    fun migration3To4_preservesTasksAndAddsSwitchTables() {
        helper.createDatabase("migration-v4", 3).apply {
            insertVersion1Project()
            insertVersion1Task()
            close()
        }
        val migrated = helper.runMigrationsAndValidate("migration-v4", 4, true, *CouchTasksDatabase.migrations())
        migrated.query("SELECT title FROM tasks WHERE uuid = 'task-1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Read", it.getString(0))
        }
        migrated.execSQL("INSERT INTO connection_profiles VALUES ('profile', '{}')")
        migrated.execSQL("INSERT INTO active_profile VALUES (1, 'profile')")
        migrated.execSQL("INSERT INTO switch_journal VALUES (1, 'destination', 'migrate', 'upload', '{}', 1, 2, 3)")
        migrated.close()
    }

    @Test
    fun migration4To5_backfillsReviewedAtFromCreatedAtWithoutDirtyingRows() {
        helper.createDatabase("migration-v5", 4).apply {
            insertVersion1Project()
            execSQL(
                """
                INSERT INTO tasks(
                    uuid, project_uuid, title, notes, status, sort_order, created_at, updated_at,
                    completed_at, sync_revision, sync_dirty
                ) VALUES (
                    'task-1', 'project-1', 'Read', '', 'pending', 1,
                    '2026-04-27T10:03:00Z', '2026-04-28T09:00:00Z', null, 9, 0
                )
                """.trimIndent(),
            )
            close()
        }
        val migrated = helper.runMigrationsAndValidate("migration-v5", 5, true, *CouchTasksDatabase.migrations())
        migrated.query("SELECT reviewed_at, created_at, updated_at, sync_revision, sync_dirty FROM tasks WHERE uuid = 'task-1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(it.getString(1), it.getString(0))
            assertEquals("2026-04-28T09:00:00Z", it.getString(2))
            assertEquals(9, it.getLong(3))
            assertEquals(0, it.getInt(4))
        }
        migrated.close()
    }

    private fun SupportSQLiteDatabase.insertVersion1Project() {
        execSQL(
            """
            INSERT INTO projects(
                uuid, name, name_normalized, created_at, updated_at, sync_revision, sync_dirty
            ) VALUES (
                'project-1', 'Inbox', 'inbox', '2026-04-27T10:03:00Z', '2026-04-27T10:03:00Z', 0, 1
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.insertVersion1Task() {
        execSQL(
            """
            INSERT INTO tasks(
                uuid, project_uuid, title, notes, status, sort_order, created_at, updated_at,
                completed_at, sync_revision, sync_dirty
            ) VALUES (
                'task-1', 'project-1', 'Read', '', 'pending', 1,
                '2026-04-27T10:03:00Z', '2026-04-27T10:03:00Z', null, 0, 1
            )
            """.trimIndent(),
        )
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
        private const val TEST_DB_V3 = "migration-test-v3.db"
    }
}
