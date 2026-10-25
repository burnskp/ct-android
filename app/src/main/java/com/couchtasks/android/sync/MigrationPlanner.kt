package com.couchtasks.android.sync

import com.couchtasks.android.data.*
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class MigrationPreview(val request: SyncRequest, val projectsAdded: Int, val tasksCopied: Int, val tasksSkipped: Int)

fun planMigration(
    sourceProjects: List<ProjectEntity>, sourceTasks: List<TaskEntity>,
    destinationProjects: List<ProjectEntity>, destinationTasks: List<TaskEntity>, checkpoint: Long,
    newId: () -> String = { UUID.randomUUID().toString() },
): MigrationPreview {
    val projects = mutableListOf<SyncProject>()
    val tasks = mutableListOf<SyncTask>()
    var skipped = 0
    sourceProjects.sortedBy { it.uuid }.forEach { local ->
        val destination = destinationProjects.firstOrNull { it.nameNormalized == normalizeProjectName(local.name) }
        val projectId = destination?.uuid ?: newId().also { id ->
            projects += SyncProject(id, local.name, normalizeProjectName(local.name), local.createdAt, local.updatedAt, local.archivedAt)
        }
        val existing = destinationTasks.filter { it.projectUuid == projectId }
        val titles = existing.map { it.title.trim().lowercase(Locale.ROOT) }.toMutableSet()
        val candidates = sourceTasks.filter { it.projectUuid == local.uuid }
            .sortedWith(compareBy<TaskEntity> { Instant.parse(it.createdAt) }.thenBy { it.uuid })
        val selected = candidates.filter { task ->
            titles.add(task.title.trim().lowercase(Locale.ROOT)).also { if (!it) skipped++ }
        }.sortedWith(compareBy<TaskEntity> { it.sortOrder }.thenBy { Instant.parse(it.createdAt) }.thenBy { it.uuid })
        var order = existing.filter { it.status == TaskStatus.Pending }.maxOfOrNull { it.sortOrder.toLong() } ?: 0L
        selected.forEach { task ->
            val next = if (task.status == TaskStatus.Pending) ++order else 0L
            require(next <= Int.MAX_VALUE) { "Destination task order is full" }
            tasks += SyncTask(newId(), projectId, next, task.title, task.notes, task.status,
                task.createdAt, task.updatedAt, task.completedAt, reviewedAt = task.reviewedAt)
        }
    }
    return MigrationPreview(SyncRequest(checkpoint, destinationProjects.map { it.toSyncProject().copy(syncDirty = false) } + projects,
        destinationTasks.map { it.toSyncTask().copy(syncDirty = false) } + tasks), projects.size, tasks.size, skipped)
}
