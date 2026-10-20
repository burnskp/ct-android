package com.couchtasks.android.sync

import androidx.room.withTransaction
import com.couchtasks.android.data.CouchTasksDatabase
import com.couchtasks.android.data.ProjectEntity
import com.couchtasks.android.data.SyncIssueEntity
import com.couchtasks.android.data.TaskStatus
import com.couchtasks.android.data.normalizeProjectName
import com.couchtasks.android.data.utcNowRfc3339

sealed interface SyncPreflightResult {
    data class Valid(val request: SyncRequest) : SyncPreflightResult
    data class Invalid(val issue: SyncIssueEntity) : SyncPreflightResult
}

/** Validates a full snapshot and performs only the two repairs defined by the protocol guide. */
class SyncPreflightValidator(
    private val db: CouchTasksDatabase,
    private val now: () -> String = ::utcNowRfc3339,
) {
    suspend fun validateAndExport(): SyncPreflightResult = db.withTransaction {
        val checkpointText = db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY)
        val checkpoint = checkpointText?.toLongOrNull() ?: if (checkpointText == null) 0 else {
            return@withTransaction invalid(0, "The local checkpoint is not an integer")
        }
        if (checkpoint < 0) return@withTransaction invalid(checkpoint, "The local checkpoint is negative")

        var projects = db.projectDao().allProjects()
        var tasks = db.taskDao().allTasks()
        val tombstones = db.tombstoneDao().allTombstones()

        projects.firstOrNull { it.uuid.isBlank() }?.let {
            return@withTransaction invalid(checkpoint, "A project has an empty identity")
        }
        tasks.firstOrNull { it.uuid.isBlank() }?.let {
            return@withTransaction invalid(checkpoint, "A task has an empty identity")
        }
        projects.firstOrNull { it.syncRevision < 0 }?.let {
            return@withTransaction invalid(checkpoint, "Project ${it.uuid} has a negative revision", it.uuid)
        }
        tasks.firstOrNull { it.syncRevision < 0 }?.let {
            return@withTransaction invalid(checkpoint, "Task ${it.uuid} has a negative revision", it.uuid)
        }
        tombstones.firstOrNull { it.syncRevision < 0 }?.let {
            return@withTransaction invalid(checkpoint, "Tombstone ${it.entityType}/${it.uuid} has a negative revision", it.uuid)
        }
        tombstones.firstOrNull { it.entityType !in VALID_ENTITY_TYPES || it.uuid.isBlank() }?.let {
            return@withTransaction invalid(checkpoint, "A tombstone has an invalid identity or entity type", it.uuid.takeIf(String::isNotBlank))
        }

        val projectIds = projects.map { it.uuid }.toSet()
        val taskIds = tasks.map { it.uuid }.toSet()
        tombstones.firstOrNull {
            (it.entityType == ENTITY_PROJECT && it.uuid in projectIds) ||
                (it.entityType == ENTITY_TASK && it.uuid in taskIds)
        }?.let {
            return@withTransaction invalid(
                checkpoint,
                "${it.entityType.replaceFirstChar(Char::uppercase)} ${it.uuid} exists as both a row and a tombstone",
                it.uuid,
            )
        }

        val desiredNames = projects.map { project ->
            val trimmed = project.name.trim()
            if (trimmed.isEmpty()) {
                return@withTransaction invalid(checkpoint, "Project ${project.uuid} has an empty name", project.uuid)
            }
            project to (trimmed to normalizeProjectName(trimmed))
        }
        desiredNames.groupBy { it.second.second }.values.firstOrNull { it.size > 1 }?.let { duplicate ->
            return@withTransaction invalid(
                checkpoint,
                "Multiple projects normalize to the same name",
                duplicate[0].first.uuid,
                duplicate[1].first.uuid,
            )
        }

        val changedProjects = desiredNames.filter { (project, desired) ->
            project.name != desired.first || project.nameNormalized != desired.second
        }
        changedProjects.forEach { (project, _) ->
            var temporary = "\u0000sync_preflight_${project.uuid}"
            while (db.projectDao().getByNormalizedName(temporary) != null) temporary += "_"
            db.projectDao().setTemporaryNormalizedName(project.uuid, temporary)
        }
        val repairTime = now()
        changedProjects.forEach { (project, desired) ->
            db.projectDao().update(
                project.copy(
                    name = desired.first,
                    nameNormalized = desired.second,
                    updatedAt = repairTime,
                    syncDirty = true,
                ),
            )
        }
        if (changedProjects.isNotEmpty()) projects = db.projectDao().allProjects()

        val validProjectIds = projects.mapTo(mutableSetOf()) { it.uuid }
        tasks.firstOrNull { it.status != TaskStatus.Pending && it.status != TaskStatus.Done }?.let {
            return@withTransaction invalid(checkpoint, "Task ${it.uuid} has invalid status '${it.status}'", it.uuid)
        }
        tasks.firstOrNull { it.projectUuid !in validProjectIds }?.let {
            return@withTransaction invalid(
                checkpoint,
                "Task ${it.uuid} references missing project ${it.projectUuid}",
                it.uuid,
                it.projectUuid,
            )
        }

        tasks.filter { it.status == TaskStatus.Pending }
            .groupBy { it.projectUuid }
            .forEach { (_, pending) ->
                pending.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }, { it.uuid }))
                    .forEachIndexed { index, task ->
                        val wanted = index + 1
                        if (task.sortOrder != wanted) {
                            db.taskDao().update(task.copy(sortOrder = wanted, updatedAt = repairTime, syncDirty = true))
                        }
                    }
            }
        tasks = db.taskDao().allTasks()

        SyncPreflightResult.Valid(
            SyncRequest(
                checkpoint = checkpoint,
                projects = projects.map(ProjectEntity::toSyncProject),
                tasks = tasks.map { it.toSyncTask() },
                tombstones = tombstones.map { it.toSyncTombstone() },
            ),
        )
    }

    private suspend fun invalid(
        checkpoint: Long,
        detail: String,
        entityUuid: String? = null,
        relatedUuid: String? = null,
    ): SyncPreflightResult.Invalid {
        val issue = SyncIssueEntity(
            kind = SyncIssueKind.INVALID_SNAPSHOT.name,
            detail = truncateSyncDetail(detail),
            requestCheckpoint = checkpoint,
            entityUuid = entityUuid,
            relatedUuid = relatedUuid,
            detectedAt = now(),
        )
        db.syncIssueDao().upsert(issue)
        return SyncPreflightResult.Invalid(issue)
    }

    private companion object {
        const val ENTITY_PROJECT = "project"
        const val ENTITY_TASK = "task"
        val VALID_ENTITY_TYPES = setOf(ENTITY_PROJECT, ENTITY_TASK)
    }
}
