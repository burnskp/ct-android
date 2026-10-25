package com.couchtasks.android.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TasksRepositoryInstrumentedTest {
    private lateinit var db: CouchTasksDatabase
    private lateinit var repository: TasksRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CouchTasksDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TasksRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun schema_hasExpectedIndexesAndConstraints() {
        assertIndex("projects", "index_projects_name_normalized", unique = true, columns = listOf("name_normalized"))
        assertIndex("projects", "index_projects_sync_dirty", unique = false, columns = listOf("sync_dirty"))
        assertIndex("tasks", "index_tasks_project_uuid", unique = false, columns = listOf("project_uuid"))
        assertIndex(
            "tasks",
            "index_tasks_project_uuid_status_sort_order",
            unique = false,
            columns = listOf("project_uuid", "status", "sort_order"),
        )
        assertIndex("tasks", "index_tasks_sync_dirty", unique = false, columns = listOf("sync_dirty"))
        assertIndex(
            "sync_tombstones",
            "index_sync_tombstones_sync_dirty",
            unique = false,
            columns = listOf("sync_dirty"),
        )
    }

    @Test
    fun createTask_setsReviewedAtToCreatedAtInWholeSeconds() = runBlocking {
        val project = repository.createProject("Home")
        val task = repository.createTask(project.uuid, "Read")

        assertEquals(task.createdAt, task.reviewedAt)
        assertTrue(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""").matches(task.reviewedAt))
    }

    @Test
    fun editTask_leavesReviewedAtUnchanged() = runBlocking {
        val project = repository.createProject("Home")
        val task = repository.createTask(project.uuid, "Read")
        db.taskDao().update(task.copy(reviewedAt = "2026-01-01T00:00:00Z", syncDirty = false))

        val edited = repository.editTask(task.uuid, "Read more", "", project.uuid, TaskStatus.Pending)

        assertEquals("2026-01-01T00:00:00Z", edited.reviewedAt)
    }

    @Test
    fun markReviewed_marksDirtyWithoutTouchingUpdatedAt() = runBlocking {
        val project = repository.createProject("Home")
        val task = repository.createTask(project.uuid, "Read")
        db.taskDao().update(
            task.copy(updatedAt = "2026-01-01T00:00:00Z", reviewedAt = "2026-01-01T00:00:00Z", syncDirty = false),
        )

        val reviewed = repository.markReviewed(task.uuid)

        assertTrue(reviewed.syncDirty)
        assertEquals("2026-01-01T00:00:00Z", reviewed.updatedAt)
        assertTrue(reviewed.reviewedAt > "2026-01-01T00:00:00Z")
        assertEquals(reviewed, db.taskDao().get(task.uuid))
    }

    @Test
    fun reviewQueue_includesInboxWithPendingAndProjectsWithStalePendingTasks() = runBlocking {
        val cutoff = "2026-01-08T00:00:00Z"
        val inbox = repository.ensureInbox()
        val stale = repository.createProject("Stale")
        val fresh = repository.createProject("Fresh")
        val doneOnly = repository.createProject("Done only")
        repository.createTask(inbox.uuid, "Sort me")
        repository.createTask(fresh.uuid, "Recent")
        repository.createTask(stale.uuid, "Old").let { db.taskDao().update(it.copy(reviewedAt = "2026-01-01T00:00:00Z")) }
        repository.createTask(doneOnly.uuid, "Old done", status = TaskStatus.Done)
            .let { db.taskDao().update(it.copy(reviewedAt = "2026-01-01T00:00:00Z")) }

        val queue = repository.observeReviewQueue(cutoff).first()

        assertEquals(listOf(inbox.uuid, stale.uuid), queue.map { it.uuid })
        assertEquals(listOf(true, false), queue.map { it.isInbox })
    }

    @Test
    fun markProjectReviewed_clearsProjectFromQueueWithoutTouchingUpdatedAt() = runBlocking {
        val cutoff = "2026-01-08T00:00:00Z"
        val project = repository.createProject("Home")
        val task = repository.createTask(project.uuid, "Read")
        db.taskDao().update(
            task.copy(updatedAt = "2026-01-01T00:00:00Z", reviewedAt = "2026-01-01T00:00:00Z", syncDirty = false),
        )
        val done = repository.createTask(project.uuid, "Done", status = TaskStatus.Done)
        db.taskDao().update(done.copy(reviewedAt = "2026-01-01T00:00:00Z", syncDirty = false))

        repository.markProjectReviewed(project.uuid)

        val reviewed = requireNotNull(db.taskDao().get(task.uuid))
        assertTrue(reviewed.syncDirty)
        assertEquals("2026-01-01T00:00:00Z", reviewed.updatedAt)
        assertTrue(reviewed.reviewedAt > cutoff)
        assertFalse(requireNotNull(db.taskDao().get(done.uuid)).syncDirty)
        assertTrue(repository.observeReviewQueue(cutoff).first().isEmpty())
    }

    @Test
    fun ensureInbox_createsDirtyRevisionZeroInboxOnlyOnce() = runBlocking {
        val first = repository.ensureInbox()
        val second = repository.ensureInbox()

        assertEquals(first.uuid, second.uuid)
        assertEquals(TasksRepository.INBOX_NAME, first.name)
        assertEquals(TasksRepository.INBOX_NORMALIZED, first.nameNormalized)
        assertEquals(0, first.syncRevision)
        assertTrue(first.syncDirty)
    }

    @Test
    fun createEditCompleteReopenReorderAndDeleteTask_keepLocalRulesAndTombstones() = runBlocking {
        val project = repository.createProject("  Home  ")
        val first = repository.createTask(project.uuid, title = "  Buy milk  ", notes = "")
        val second = repository.createTask(project.uuid, title = "Call Sam", notes = "notes")
        val third = repository.createTask(project.uuid, title = "Water plants")

        assertEquals("Home", project.name)
        assertEquals("home", project.nameNormalized)
        assertTrue(project.syncDirty)
        assertEquals(1, first.sortOrder)
        assertEquals(2, second.sortOrder)
        assertEquals(3, third.sortOrder)
        assertEquals("", first.notes)

        val renamed = repository.renameProject(project.uuid, " House ")
        assertEquals("House", renamed.name)
        assertEquals(project.syncRevision, renamed.syncRevision)
        assertTrue(renamed.syncDirty)

        val edited = repository.editTask(
            taskUuid = second.uuid,
            title = " Call Alex ",
            notes = " updated ",
            projectUuid = project.uuid,
            status = TaskStatus.Pending,
        )
        assertEquals("Call Alex", edited.title)
        assertEquals(" updated ", edited.notes)
        assertEquals(second.syncRevision, edited.syncRevision)
        assertTrue(edited.syncDirty)

        val completed = repository.completeTask(first.uuid)
        assertEquals(TaskStatus.Done, completed.status)
        assertEquals(0, completed.sortOrder)
        assertNotNull(completed.completedAt)
        assertTrue(completed.syncDirty)
        assertEquals(listOf(second.uuid to 1, third.uuid to 2), pendingUuidOrders(project.uuid))

        val reopened = repository.reopenTask(first.uuid)
        assertEquals(TaskStatus.Pending, reopened.status)
        assertNull(reopened.completedAt)
        assertEquals(3, reopened.sortOrder)
        assertEquals(listOf(second.uuid to 1, third.uuid to 2, first.uuid to 3), pendingUuidOrders(project.uuid))

        repository.reorderPendingTasks(project.uuid, listOf(first.uuid, second.uuid, third.uuid))
        assertEquals(listOf(first.uuid to 1, second.uuid to 2, third.uuid to 3), pendingUuidOrders(project.uuid))

        repository.deleteTask(second.uuid)
        assertNull(db.taskDao().get(second.uuid))
        assertEquals(listOf(first.uuid to 1, third.uuid to 2), pendingUuidOrders(project.uuid))
        val taskTombstone = db.tombstoneDao().get(TasksRepository.ENTITY_TASK, second.uuid)
        assertNotNull(taskTombstone)
        assertTrue(taskTombstone!!.syncDirty)
        assertEquals(second.syncRevision, taskTombstone.syncRevision)
    }

    @Test
    fun deleteProject_removesChildrenAndCreatesProjectTombstoneTransactionally() = runBlocking {
        val project = repository.createProject("Errands")
        val child = repository.createTask(project.uuid, "Groceries")
        val otherProject = repository.createProject("Work")
        val otherTask = repository.createTask(otherProject.uuid, "Email")

        repository.deleteProject(project.uuid)

        assertNull(db.projectDao().get(project.uuid))
        assertNull(db.taskDao().get(child.uuid))
        assertEquals(emptyList<TaskEntity>(), db.taskDao().tasksForProject(project.uuid))
        assertNotNull(db.projectDao().get(otherProject.uuid))
        assertNotNull(db.taskDao().get(otherTask.uuid))
        val childTombstone = db.tombstoneDao().get(TasksRepository.ENTITY_TASK, child.uuid)
        assertNotNull(childTombstone)
        assertTrue(childTombstone!!.syncDirty)
        assertEquals(child.syncRevision, childTombstone.syncRevision)
        val projectTombstone = db.tombstoneDao().get(TasksRepository.ENTITY_PROJECT, project.uuid)
        assertNotNull(projectTombstone)
        assertTrue(projectTombstone!!.syncDirty)
        assertEquals(project.syncRevision, projectTombstone.syncRevision)
    }

    @Test
    fun deleteProjects_removesAllSelectedAndCreatesTombstones() = runBlocking {
        val first = repository.createProject("Errands")
        val child = repository.createTask(first.uuid, "Groceries")
        val second = repository.createProject("Empty")
        val kept = repository.createProject("Work")

        repository.deleteProjects(listOf(first.uuid, second.uuid))

        assertNull(db.projectDao().get(first.uuid))
        assertNull(db.projectDao().get(second.uuid))
        assertNull(db.taskDao().get(child.uuid))
        assertNotNull(db.projectDao().get(kept.uuid))
        assertNotNull(db.tombstoneDao().get(TasksRepository.ENTITY_TASK, child.uuid))
        assertNotNull(db.tombstoneDao().get(TasksRepository.ENTITY_PROJECT, first.uuid))
        assertNotNull(db.tombstoneDao().get(TasksRepository.ENTITY_PROJECT, second.uuid))
    }

    @Test
    fun deleteProjects_rollsBackWhenAnyProjectIsMissing() = runBlocking {
        val project = repository.createProject("Errands")
        val child = repository.createTask(project.uuid, "Groceries")

        val result = runCatching { repository.deleteProjects(listOf(project.uuid, "missing")) }

        assertTrue(result.isFailure)
        assertNotNull(db.projectDao().get(project.uuid))
        assertNotNull(db.taskDao().get(child.uuid))
        assertNull(db.tombstoneDao().get(TasksRepository.ENTITY_PROJECT, project.uuid))
    }

    @Test
    fun observeProjectsWithCounts_sortsInboxThenPendingProjectsThenEmptyProjects() = runBlocking {
        val inbox = repository.ensureInbox()
        val emptyAlpha = repository.createProject("Alpha Empty")
        val pendingZulu = repository.createProject("Zulu Pending")
        val doneOnly = repository.createProject("Beta Done")
        val pendingAlpha = repository.createProject("Alpha Pending")

        repository.createTask(pendingZulu.uuid, "Pending Z")
        repository.createTask(pendingAlpha.uuid, "Pending A")
        repository.completeTask(repository.createTask(doneOnly.uuid, "Already done").uuid)
        repository.completeTask(repository.createTask(emptyAlpha.uuid, "No pending").uuid)

        val projects = repository.observeProjectsWithCounts().first()

        assertEquals(
            listOf(inbox.uuid, pendingAlpha.uuid, pendingZulu.uuid, emptyAlpha.uuid, doneOnly.uuid),
            projects.map { it.uuid },
        )
        assertEquals(listOf(0, 1, 1, 0, 0), projects.map { it.pendingCount })
    }

    @Test
    fun taskCanMoveProjectAndStatusInOneEdit_withCompactedSourceOrder() = runBlocking {
        val source = repository.createProject("Source")
        val destination = repository.createProject("Destination")
        val moved = repository.createTask(source.uuid, "Move me")
        val remaining = repository.createTask(source.uuid, "Stay")

        val edited = repository.editTask(
            taskUuid = moved.uuid,
            title = moved.title,
            notes = moved.notes,
            projectUuid = destination.uuid,
            status = TaskStatus.Done,
        )

        assertEquals(destination.uuid, edited.projectUuid)
        assertEquals(TaskStatus.Done, edited.status)
        assertNotNull(edited.completedAt)
        assertEquals(0, edited.sortOrder)
        assertEquals(listOf(remaining.uuid to 1), pendingUuidOrders(source.uuid))
        assertEquals(emptyList<Pair<String, Int>>(), pendingUuidOrders(destination.uuid))
    }

    @Test
    fun undoCompleteTask_restoresOriginalPendingPosition() = runBlocking {
        val project = repository.createProject("Undo")
        val first = repository.createTask(project.uuid, "First")
        val second = repository.createTask(project.uuid, "Second")
        val third = repository.createTask(project.uuid, "Third")

        repository.completeTask(second.uuid)
        assertEquals(listOf(first.uuid to 1, third.uuid to 2), pendingUuidOrders(project.uuid))

        val restored = repository.undoCompleteTask(second.uuid, previousSortOrder = 2)
        assertEquals(TaskStatus.Pending, restored.status)
        assertNull(restored.completedAt)
        assertEquals(2, restored.sortOrder)
        assertTrue(restored.syncDirty)
        assertEquals(listOf(first.uuid to 1, second.uuid to 2, third.uuid to 3), pendingUuidOrders(project.uuid))

        val unchanged = repository.undoCompleteTask(second.uuid, previousSortOrder = 1)
        assertEquals(2, unchanged.sortOrder)
        assertEquals(listOf(first.uuid to 1, second.uuid to 2, third.uuid to 3), pendingUuidOrders(project.uuid))
    }

    private suspend fun pendingUuidOrders(projectUuid: String): List<Pair<String, Int>> =
        db.taskDao().pendingTasks(projectUuid).map { it.uuid to it.sortOrder }

    private fun assertIndex(table: String, indexName: String, unique: Boolean, columns: List<String>) {
        var found = false
        db.openHelper.readableDatabase.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val uniqueIndex = cursor.getColumnIndex("unique")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == indexName) {
                    found = true
                    assertEquals("$indexName unique flag", if (unique) 1 else 0, cursor.getInt(uniqueIndex))
                }
            }
        }
        assertTrue("Missing index $indexName on $table", found)

        val actualColumns = mutableListOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA index_info(`$indexName`)").use { cursor ->
            val columnNameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) actualColumns += cursor.getString(columnNameIndex)
        }
        assertEquals(columns, actualColumns)
    }
}
