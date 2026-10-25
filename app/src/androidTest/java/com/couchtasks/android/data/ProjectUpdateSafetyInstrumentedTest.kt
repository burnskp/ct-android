package com.couchtasks.android.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.couchtasks.android.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectUpdateSafetyInstrumentedTest {
    @Test fun projectUpdatesPreserveChildrenAndConflictsRollback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CouchTasksDatabase::class.java).build()
        try {
            val repo = TasksRepository(db)
            val home = repo.createProject("Home")
            val work = repo.createProject("Work")
            val pending = repo.createTask(home.uuid, "Read")
            val done = repo.createTask(home.uuid, "Done", status = TaskStatus.Done)
            val applier = SyncResponseApplier(db)
            applier.apply(SyncResponse(1, listOf(home.toSyncProject().copy(syncRevision = 1))))
            assertEquals(listOf(pending.uuid, done.uuid).toSet(), db.taskDao().tasksForProject(home.uuid).map { it.uuid }.toSet())
            val snapshot = SyncExporter(db).exportRequest()
            assertTrue(runCatching {
                applier.apply(SyncResponse(2, listOf(work.toSyncProject().copy(name = "Other", nameNormalized = "other"),
                    home.toSyncProject().copy(name = "Other", nameNormalized = "other", syncRevision = 2))))
            }.isFailure)
            assertEquals(snapshot, SyncExporter(db).exportRequest())
            applier.apply(SyncResponse(2, listOf(home.toSyncProject().copy(uuid = "canonical", syncRevision = 2))))
            assertEquals(2, db.taskDao().tasksForProject("canonical").size)
            applier.apply(SyncResponse(3, tombstones = listOf(SyncTombstone("project", "canonical", home.updatedAt, 3, false))))
            assertTrue(db.taskDao().allTasks().isEmpty())
        } finally { db.close() }
    }
}
