package com.couchtasks.android.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class TasksRepository(
    private val db: CouchTasksDatabase,
) {
    private val projects = db.projectDao()
    private val tasks = db.taskDao()
    private val tombstones = db.tombstoneDao()

    fun observeProjectsWithCounts(): Flow<List<ProjectWithCounts>> = projects.observeProjectsWithCounts()

    fun observePendingTasks(projectUuid: String): Flow<List<TaskEntity>> =
        tasks.observeProjectTasksByStatus(projectUuid, TaskStatus.Pending)

    fun observeDoneTasks(projectUuid: String): Flow<List<TaskEntity>> =
        tasks.observeDoneProjectTasks(projectUuid)

    fun observeAllTasks(): Flow<List<TaskEntity>> = tasks.observeAllTasks()

    fun observeReviewQueue(cutoff: String): Flow<List<ReviewProject>> = projects.observeReviewQueue(cutoff)

    fun observeSyncIssue(): Flow<SyncIssueEntity?> = db.syncIssueDao().observe()

    suspend fun getProject(uuid: String): ProjectEntity? = projects.get(uuid)

    suspend fun getTask(uuid: String): TaskEntity? = tasks.get(uuid)

    suspend fun allProjects(): List<ProjectEntity> = projects.allProjects()

    suspend fun ensureInbox(): ProjectEntity = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        db.withTransaction {
            projects.getByNormalizedName(INBOX_NORMALIZED) ?: ProjectEntity(
                uuid = UUID.randomUUID().toString(),
                name = INBOX_NAME,
                nameNormalized = INBOX_NORMALIZED,
                createdAt = utcNowRfc3339(),
                updatedAt = utcNowRfc3339(),
                syncRevision = 0,
                syncDirty = true,
            ).also { projects.insert(it) }
        }
    }

    suspend fun createProject(name: String): ProjectEntity = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        val trimmed = requireProjectName(name)
        val normalized = normalizeProjectName(trimmed)
        db.withTransaction {
            require(projects.getByNormalizedName(normalized) == null) { "Project already exists" }
            val now = utcNowRfc3339()
            ProjectEntity(
                uuid = UUID.randomUUID().toString(),
                name = trimmed,
                nameNormalized = normalized,
                createdAt = now,
                updatedAt = now,
                syncRevision = 0,
                syncDirty = true,
            ).also { projects.insert(it) }
        }
    }

    suspend fun renameProject(projectUuid: String, name: String): ProjectEntity = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        val trimmed = requireProjectName(name)
        val normalized = normalizeProjectName(trimmed)
        db.withTransaction {
            val existingWithName = projects.getByNormalizedName(normalized)
            require(existingWithName == null || existingWithName.uuid == projectUuid) { "Project already exists" }
            val project = requireNotNull(projects.get(projectUuid)) { "Project not found" }
            project.copy(
                name = trimmed,
                nameNormalized = normalized,
                updatedAt = utcNowRfc3339(),
                syncDirty = true,
            ).also { projects.update(it) }
        }
    }

    suspend fun deleteProject(projectUuid: String) = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        db.withTransaction { deleteProjectInTransaction(projectUuid) }
    }

    suspend fun deleteProjects(projectUuids: Collection<String>) = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        db.withTransaction { projectUuids.forEach { deleteProjectInTransaction(it) } }
    }

    private suspend fun deleteProjectInTransaction(projectUuid: String) {
        val project = requireNotNull(projects.get(projectUuid)) { "Project not found" }
        val now = utcNowRfc3339()
        tasks.tasksForProject(projectUuid).forEach { task ->
            tombstones.upsert(
                SyncTombstoneEntity(
                    entityType = ENTITY_TASK,
                    uuid = task.uuid,
                    syncRevision = task.syncRevision,
                    deletedAt = now,
                    syncDirty = true,
                ),
            )
        }
        tombstones.upsert(
            SyncTombstoneEntity(
                entityType = ENTITY_PROJECT,
                uuid = project.uuid,
                syncRevision = project.syncRevision,
                deletedAt = now,
                syncDirty = true,
            ),
        )
        tasks.deleteForProject(projectUuid)
        projects.delete(projectUuid)
    }

    suspend fun createTask(
        projectUuid: String,
        title: String,
        notes: String = "",
        status: String = TaskStatus.Pending,
    ): TaskEntity = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        val trimmedTitle = requireTaskTitle(title)
        val validStatus = TaskStatus.requireValid(status)
        db.withTransaction {
            requireNotNull(projects.get(projectUuid)) { "Project not found" }
            val now = utcNowRfc3339()
            val sortOrder = if (validStatus == TaskStatus.Pending) tasks.maxPendingSortOrder(projectUuid) + 1 else 0
            TaskEntity(
                uuid = UUID.randomUUID().toString(),
                projectUuid = projectUuid,
                title = trimmedTitle,
                notes = notes.ifBlank { "" },
                status = validStatus,
                sortOrder = sortOrder,
                createdAt = now,
                updatedAt = now,
                completedAt = if (validStatus == TaskStatus.Done) now else null,
                syncRevision = 0,
                syncDirty = true,
                reviewedAt = now,
            ).also { tasks.insert(it) }
        }
    }

    suspend fun editTask(
        taskUuid: String,
        title: String,
        notes: String,
        projectUuid: String,
        status: String,
    ): TaskEntity = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        val trimmedTitle = requireTaskTitle(title)
        val validStatus = TaskStatus.requireValid(status)
        db.withTransaction {
            requireNotNull(projects.get(projectUuid)) { "Project not found" }
            val task = requireNotNull(tasks.get(taskUuid)) { "Task not found" }
            val now = utcNowRfc3339()
            val movingProject = task.projectUuid != projectUuid
            val newSortOrder = when {
                validStatus == TaskStatus.Done -> 0
                task.status == TaskStatus.Pending && !movingProject -> task.sortOrder
                else -> tasks.maxPendingSortOrder(projectUuid) + 1
            }
            val completedAt = when {
                validStatus == TaskStatus.Done && task.status != TaskStatus.Done -> now
                validStatus == TaskStatus.Done -> task.completedAt ?: now
                else -> null
            }
            task.copy(
                projectUuid = projectUuid,
                title = trimmedTitle,
                notes = notes.ifBlank { "" },
                status = validStatus,
                sortOrder = newSortOrder,
                updatedAt = now,
                completedAt = completedAt,
                syncDirty = true,
            ).also {
                tasks.update(it)
                if (task.status == TaskStatus.Pending) compactPendingOrders(task.projectUuid)
                if (validStatus == TaskStatus.Pending) compactPendingOrders(projectUuid)
            }
        }
    }

    suspend fun completeTask(taskUuid: String): TaskEntity = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        db.withTransaction {
            val task = requireNotNull(tasks.get(taskUuid)) { "Task not found" }
            if (task.status == TaskStatus.Done) return@withTransaction task
            val now = utcNowRfc3339()
            val transition = transitionTaskStatus(
                currentStatus = task.status,
                requestedStatus = TaskStatus.Done,
                currentPendingSortOrder = task.sortOrder,
                currentCompletedAt = task.completedAt,
                appendedPendingSortOrder = 0,
                now = now,
            )
            task.copy(
                status = transition.status,
                sortOrder = transition.sortOrder,
                updatedAt = now,
                completedAt = transition.completedAt,
                syncDirty = true,
            ).also {
                tasks.update(it)
                if (transition.pendingOrderNeedsCompaction) compactPendingOrders(task.projectUuid)
            }
        }
    }

    suspend fun reopenTask(taskUuid: String): TaskEntity = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        db.withTransaction {
            val task = requireNotNull(tasks.get(taskUuid)) { "Task not found" }
            if (task.status == TaskStatus.Pending) return@withTransaction task
            val now = utcNowRfc3339()
            val transition = transitionTaskStatus(
                currentStatus = task.status,
                requestedStatus = TaskStatus.Pending,
                currentPendingSortOrder = task.sortOrder,
                currentCompletedAt = task.completedAt,
                appendedPendingSortOrder = tasks.maxPendingSortOrder(task.projectUuid) + 1,
                now = now,
            )
            task.copy(
                status = transition.status,
                sortOrder = transition.sortOrder,
                updatedAt = now,
                completedAt = transition.completedAt,
                syncDirty = true,
            ).also { tasks.update(it) }
        }
    }

    suspend fun undoCompleteTask(taskUuid: String, previousSortOrder: Int): TaskEntity = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        db.withTransaction {
            val task = requireNotNull(tasks.get(taskUuid)) { "Task not found" }
            if (task.status != TaskStatus.Done) return@withTransaction task
            val now = utcNowRfc3339()
            val restored = task.copy(
                status = TaskStatus.Pending,
                sortOrder = 0,
                updatedAt = now,
                completedAt = null,
                syncDirty = true,
            )
            tasks.update(restored)
            val others = tasks.pendingTasks(task.projectUuid).filterNot { it.uuid == taskUuid }
            val ordered = others.toMutableList().apply {
                add((previousSortOrder - 1).coerceIn(0, others.size), restored)
            }
            ordered.forEachIndexed { index, pending ->
                val wanted = index + 1
                if (pending.sortOrder != wanted) {
                    tasks.update(pending.copy(sortOrder = wanted, updatedAt = now, syncDirty = true))
                }
            }
            tasks.get(taskUuid) ?: restored
        }
    }

    /** An explicit review: syncs the new `reviewed_at` without counting as an edit, like the CLI's `mark_reviewed`. */
    suspend fun markReviewed(taskUuid: String): TaskEntity = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        db.withTransaction {
            val task = requireNotNull(tasks.get(taskUuid)) { "Task not found" }
            task.copy(reviewedAt = utcNowRfc3339(), syncDirty = true).also { tasks.update(it) }
        }
    }

    /** Reviews every pending task in a project at once; like [markReviewed], it leaves `updated_at` alone. */
    suspend fun markProjectReviewed(projectUuid: String) = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        db.withTransaction {
            requireNotNull(projects.get(projectUuid)) { "Project not found" }
            tasks.markPendingReviewed(projectUuid, utcNowRfc3339())
        }
    }

    suspend fun deleteTask(taskUuid: String) = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        db.withTransaction {
            val task = requireNotNull(tasks.get(taskUuid)) { "Task not found" }
            tombstones.upsert(
                SyncTombstoneEntity(
                    entityType = ENTITY_TASK,
                    uuid = task.uuid,
                    syncRevision = task.syncRevision,
                    deletedAt = utcNowRfc3339(),
                    syncDirty = true,
                ),
            )
            tasks.delete(taskUuid)
            if (task.status == TaskStatus.Pending) compactPendingOrders(task.projectUuid)
        }
    }

    suspend fun moveOrphanTask(taskUuid: String, projectUuid: String): TaskEntity = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        db.withTransaction {
            requireNotNull(projects.get(projectUuid)) { "Project not found" }
            val task = requireNotNull(tasks.get(taskUuid)) { "Task not found" }
            val oldProjectUuid = task.projectUuid
            val now = utcNowRfc3339()
            val moved = task.copy(
                projectUuid = projectUuid,
                sortOrder = if (task.status == TaskStatus.Pending) tasks.maxPendingSortOrder(projectUuid) + 1 else 0,
                updatedAt = now,
                syncDirty = true,
            )
            tasks.update(moved)
            if (task.status == TaskStatus.Pending) {
                compactPendingOrders(oldProjectUuid)
                compactPendingOrders(projectUuid)
            }
            tasks.get(taskUuid) ?: moved
        }
    }

    suspend fun clearDoneTasks(projectUuid: String) = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        db.withTransaction {
            requireNotNull(projects.get(projectUuid)) { "Project not found" }
            val now = utcNowRfc3339()
            tasks.doneTasks(projectUuid).forEach { task ->
                tombstones.upsert(
                    SyncTombstoneEntity(
                        entityType = ENTITY_TASK,
                        uuid = task.uuid,
                        syncRevision = task.syncRevision,
                        deletedAt = now,
                        syncDirty = true,
                    ),
                )
                tasks.delete(task.uuid)
            }
        }
    }

    suspend fun reorderPendingTasks(projectUuid: String, orderedTaskUuids: List<String>) = WriteSyncMutex.mutex.withLock {
        db.requireNotSwitching()
        db.withTransaction {
            requireNotNull(projects.get(projectUuid)) { "Project not found" }
            val current = tasks.pendingTasks(projectUuid)
            val byUuid = current.associateBy { it.uuid }
            val reordered = orderedTaskUuids.mapNotNull(byUuid::get) + current.filterNot { it.uuid in orderedTaskUuids.toSet() }
            val now = utcNowRfc3339()
            reordered.forEachIndexed { index, task ->
                val wanted = index + 1
                if (task.sortOrder != wanted) {
                    tasks.update(task.copy(sortOrder = wanted, updatedAt = now, syncDirty = true))
                }
            }
        }
    }

    private suspend fun compactPendingOrders(projectUuid: String) {
        val now = utcNowRfc3339()
        tasks.pendingTasks(projectUuid).forEachIndexed { index, task ->
            val wanted = index + 1
            if (task.sortOrder != wanted) {
                tasks.update(task.copy(sortOrder = wanted, updatedAt = now, syncDirty = true))
            }
        }
    }

    companion object {
        const val INBOX_NAME = "Inbox"
        const val INBOX_NORMALIZED = "inbox"
        const val ENTITY_PROJECT = "project"
        const val ENTITY_TASK = "task"
    }
}
