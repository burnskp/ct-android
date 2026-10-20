package com.couchtasks.android.sync

import androidx.room.withTransaction
import com.couchtasks.android.data.CouchTasksDatabase
import com.couchtasks.android.data.TasksRepository.Companion.ENTITY_PROJECT
import com.couchtasks.android.data.TasksRepository.Companion.ENTITY_TASK
import com.couchtasks.android.data.ProjectEntity
import com.couchtasks.android.data.SyncStateEntity
import com.couchtasks.android.data.SyncTombstoneEntity
import com.couchtasks.android.data.TaskEntity
import com.couchtasks.android.data.TaskStatus
import com.couchtasks.android.data.normalizeProjectName
import com.couchtasks.android.data.requireProjectName
import com.couchtasks.android.data.requireTaskTitle

class InvalidSyncResponseException(message: String) : IllegalArgumentException(message)

class SyncResponseApplier(
    private val db: CouchTasksDatabase,
) {
    suspend fun apply(response: SyncResponse) {
        db.withTransaction {
            validateResponse(response)
            response.projects.forEach { applyProject(it) }
            response.tasks.forEach { applyTask(it) }
            response.tombstones.forEach { applyTombstone(it) }
            db.syncStateDao().set(SyncStateEntity(SyncExporter.CHECKPOINT_KEY, response.checkpoint.toString()))
            db.syncIssueDao().clear()
        }
    }

    private suspend fun validateResponse(response: SyncResponse) {
        if (response.checkpoint < 0) throw InvalidSyncResponseException("Response checkpoint is negative")
        val currentCheckpoint = db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY)?.toLongOrNull() ?: 0
        if (response.checkpoint < currentCheckpoint) {
            throw InvalidSyncResponseException(
                "Response checkpoint ${response.checkpoint} is behind local checkpoint $currentCheckpoint",
            )
        }
        response.projects.firstOrNull { it.syncRevision < 0 }?.let {
            throw InvalidSyncResponseException("Project ${it.uuid} has a negative revision")
        }
        response.tasks.firstOrNull { it.syncRevision < 0 }?.let {
            throw InvalidSyncResponseException("Task ${it.uuid} has a negative revision")
        }
        response.tombstones.firstOrNull { it.syncRevision < 0 }?.let {
            throw InvalidSyncResponseException("Tombstone ${it.entityType}/${it.uuid} has a negative revision")
        }
        if (response.projects.map { it.uuid }.distinct().size != response.projects.size ||
            response.tasks.map { it.uuid }.distinct().size != response.tasks.size ||
            response.tombstones.map { it.entityType to it.uuid }.distinct().size != response.tombstones.size
        ) {
            throw InvalidSyncResponseException("Response contains duplicate identities")
        }
    }

    private suspend fun applyProject(project: SyncProject) {
        if (hasNewerOrEqualTombstone(ENTITY_PROJECT, project.uuid, project.syncRevision)) return

        val entity = project.toCleanEntity()
        val projects = db.projectDao()
        val byUuid = projects.get(project.uuid)
        if (byUuid != null) {
            rejectRevisionRegression("Project ${project.uuid}", localRevision = byUuid.syncRevision, incomingRevision = project.syncRevision)
            val conflict = projects.getByNormalizedName(entity.nameNormalized)
            if (conflict != null && conflict.uuid != entity.uuid) {
                throw InvalidSyncResponseException("Project ${project.uuid} conflicts with existing normalized name")
            }
            projects.update(entity)
            return
        }

        val byName = projects.getByNormalizedName(project.nameNormalized)
        if (byName == null || byName.uuid == project.uuid) {
            projects.insert(entity)
            return
        }

        rejectRevisionRegression(
            "Project name conflict ${project.nameNormalized}",
            localRevision = byName.syncRevision,
            incomingRevision = project.syncRevision,
        )
        projects.setTemporaryNormalizedName(byName.uuid, temporaryNormalizedName(byName))
        projects.insert(entity)
        db.taskDao().moveToProject(oldProjectUuid = byName.uuid, newProjectUuid = project.uuid)
        projects.delete(byName.uuid)
    }

    private suspend fun applyTask(task: SyncTask) {
        if (hasNewerOrEqualTombstone(ENTITY_TASK, task.uuid, task.syncRevision)) return
        if (db.projectDao().get(task.projectUuid) == null) {
            throw InvalidSyncResponseException("Task ${task.uuid} references missing project ${task.projectUuid}")
        }
        db.taskDao().get(task.uuid)?.let { existing ->
            rejectRevisionRegression("Task ${task.uuid}", localRevision = existing.syncRevision, incomingRevision = task.syncRevision)
        }
        db.taskDao().upsert(task.toCleanEntity())
    }

    private suspend fun applyTombstone(tombstone: SyncTombstone) {
        if (tombstone.entityType != ENTITY_TASK && tombstone.entityType != ENTITY_PROJECT) {
            throw InvalidSyncResponseException("Unsupported tombstone entity type: ${tombstone.entityType}")
        }
        db.tombstoneDao().get(tombstone.entityType, tombstone.uuid)?.let { existing ->
            if (existing.syncRevision > tombstone.syncRevision) return
        }
        when (tombstone.entityType) {
            ENTITY_TASK -> db.taskDao().get(tombstone.uuid)?.let { task ->
                if (task.syncRevision > tombstone.syncRevision) return
            }
            ENTITY_PROJECT -> db.projectDao().get(tombstone.uuid)?.let { project ->
                if (project.syncRevision > tombstone.syncRevision) return
            }
        }

        val entity = SyncTombstoneEntity(
            entityType = tombstone.entityType,
            uuid = tombstone.uuid,
            syncRevision = tombstone.syncRevision,
            deletedAt = tombstone.deletedAt,
            syncDirty = false,
        )
        db.tombstoneDao().upsert(entity)
        when (tombstone.entityType) {
            ENTITY_TASK -> db.taskDao().delete(tombstone.uuid)
            ENTITY_PROJECT -> {
                db.taskDao().deleteForProject(tombstone.uuid)
                db.projectDao().delete(tombstone.uuid)
            }
        }
    }

    private suspend fun hasNewerOrEqualTombstone(entityType: String, uuid: String, revision: Long): Boolean =
        db.tombstoneDao().get(entityType, uuid)?.let { it.syncRevision >= revision } == true

    private fun SyncProject.toCleanEntity(): ProjectEntity {
        val trimmedName = requireProjectName(name)
        val normalized = normalizeProjectName(trimmedName)
        if (nameNormalized != normalized) {
            throw InvalidSyncResponseException("Project $uuid has inconsistent normalized name")
        }
        return ProjectEntity(
            uuid = uuid,
            name = trimmedName,
            nameNormalized = normalized,
            createdAt = createdAt,
            updatedAt = updatedAt,
            archivedAt = archivedAt,
            syncRevision = syncRevision,
            syncDirty = false,
        )
    }

    private fun SyncTask.toCleanEntity(): TaskEntity {
        val validStatus = TaskStatus.requireValid(status)
        val validTitle = requireTaskTitle(title)
        return TaskEntity(
            uuid = uuid,
            projectUuid = projectUuid,
            title = validTitle,
            notes = notes.ifBlank { "" },
            status = validStatus,
            sortOrder = if (validStatus == TaskStatus.Pending) validatedPendingSortOrder() else 0,
            createdAt = createdAt,
            updatedAt = updatedAt,
            completedAt = if (validStatus == TaskStatus.Done) completedAt else null,
            syncRevision = syncRevision,
            syncDirty = false,
        )
    }

    private fun rejectRevisionRegression(label: String, localRevision: Long, incomingRevision: Long) {
        if (localRevision > incomingRevision) {
            throw InvalidSyncResponseException(
                "$label has local revision $localRevision newer than incoming revision $incomingRevision",
            )
        }
    }

    private fun SyncTask.validatedPendingSortOrder(): Int {
        if (sortOrder !in 1..Int.MAX_VALUE.toLong()) {
            throw InvalidSyncResponseException("Task $uuid has invalid pending sort_order $sortOrder")
        }
        return sortOrder.toInt()
    }

    private fun temporaryNormalizedName(project: ProjectEntity): String =
        "${project.nameNormalized}__local_${project.uuid}"
}
