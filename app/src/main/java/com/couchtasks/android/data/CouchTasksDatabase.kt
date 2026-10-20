package com.couchtasks.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConnectionProfile::class, ActiveProfile::class, SwitchJournal::class,
        ProjectEntity::class,
        TaskEntity::class,
        SyncTombstoneEntity::class,
        SyncStateEntity::class,
        SyncIssueEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class CouchTasksDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun projectDao(): ProjectDao
    abstract fun taskDao(): TaskDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun syncIssueDao(): SyncIssueDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN archived_at TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_issue` (
                        `id` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `http_status` INTEGER,
                        `detail` TEXT NOT NULL,
                        `request_checkpoint` INTEGER NOT NULL,
                        `entity_uuid` TEXT,
                        `related_uuid` TEXT,
                        `server_checkpoint` INTEGER,
                        `detected_at` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS connection_profiles (id TEXT NOT NULL PRIMARY KEY, settings TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS active_profile (id INTEGER NOT NULL PRIMARY KEY, profileId TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS switch_journal (id INTEGER NOT NULL PRIMARY KEY, profileId TEXT NOT NULL, mode TEXT NOT NULL, phase TEXT NOT NULL, request TEXT NOT NULL, projectsAdded INTEGER NOT NULL, tasksCopied INTEGER NOT NULL, tasksSkipped INTEGER NOT NULL)")
            }
        }

        fun migrations(): Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun create(context: Context): CouchTasksDatabase = Room.databaseBuilder(
            context.applicationContext,
            CouchTasksDatabase::class.java,
            "couch-tasks.db",
        )
            .addMigrations(*migrations())
            .build()
    }
}
