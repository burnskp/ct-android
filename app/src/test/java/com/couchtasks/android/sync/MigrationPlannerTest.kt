package com.couchtasks.android.sync

import com.couchtasks.android.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MigrationPlannerTest {
    private val time = "2026-01-01T00:00:00Z"
    private fun project(id: String, name: String) = ProjectEntity(id, name, normalizeProjectName(name), time, time)
    private fun task(id: String, project: String, title: String, order: Int = 1, status: String = "pending", created: String = time) =
        TaskEntity(id, project, title, "notes-$id", status, order, created, time, if (status == "done") time else null, 44, true, "reviewed-$id")

    @Test fun destinationWinsAndIncomingDuplicatesChooseEarliestThenUuid() = runBlocking<Unit> {
        val local = project("local", " WORK ")
        val remote = project("remote", "Work")
        var id = 0
        val result = planMigration(listOf(local), listOf(
            task("skip", "local", " Match ", status = "done"),
            task("z", "local", "copy", 2), task("a", "local", " COPY ", 3),
            task("earliest", "local", "other", 4, created = "2025-01-01T00:00:00Z"),
            task("later", "local", "OTHER", 1),
        ), listOf(remote), listOf(task("dest", "remote", "match"), task("duplicate", "remote", "MATCH", 2)), 91) { "fresh-${++id}" }
        assertEquals(0, result.projectsAdded)
        assertEquals(2, result.tasksCopied)
        assertEquals(3, result.tasksSkipped)
        assertEquals(91L, result.request.checkpoint)
        val copies = result.request.tasks.filter { it.syncDirty }
        assertEquals(listOf("notes-a", "notes-earliest"), copies.map { it.notes })
        assertEquals(listOf("reviewed-a", "reviewed-earliest"), copies.map { it.reviewedAt })
        assertEquals(listOf(3L, 4L), copies.map { it.sortOrder })
        assertTrue(copies.all { it.projectUuid == "remote" && it.syncRevision == 0L && it.uuid.startsWith("fresh-") })
        assertEquals(listOf("dest", "duplicate"), result.request.tasks.filterNot { it.syncDirty }.map { it.uuid })
        assertTrue(result.request.tombstones.isEmpty())
    }

    @Test fun emptyProjectsAndCompletedTasksAreCopiedAcrossDistinctProjects() = runBlocking<Unit> {
        val source = listOf(project("p", "Home"), project("q", "Work"), project("empty", "Empty"))
        val result = planMigration(source, listOf(task("a", "p", "Same", status = "done"), task("b", "q", "Same")), emptyList(), emptyList(), 4)
        assertEquals(3, result.projectsAdded)
        assertEquals(2, result.tasksCopied)
        assertEquals(0, result.tasksSkipped)
        assertEquals(0L, result.request.tasks.single { it.status == "done" }.sortOrder)
        assertEquals(time, result.request.tasks.single { it.status == "done" }.completedAt)
        assertTrue(result.request.projects.none { it.uuid in source.map { p -> p.uuid } })
        assertEquals(2, result.request.tasks.map { it.projectUuid }.distinct().size)
    }
}
