package com.couchtasks.android.sync

import androidx.room.withTransaction
import com.couchtasks.android.data.CouchTasksDatabase
import com.couchtasks.android.data.ProjectEntity
import com.couchtasks.android.data.SyncTombstoneEntity
import com.couchtasks.android.data.TaskEntity

class SyncExporter(private val db: CouchTasksDatabase) {
    suspend fun exportRequest(): SyncRequest = db.withTransaction {
        SyncRequest(
            checkpoint = db.syncStateDao().get(CHECKPOINT_KEY)?.toLongOrNull() ?: 0,
            projects = db.projectDao().allProjects().map { it.toSyncProject() },
            tasks = db.taskDao().allTasks().map { it.toSyncTask() },
            tombstones = db.tombstoneDao().allTombstones().map { it.toSyncTombstone() },
        )
    }

    companion object {
        const val CHECKPOINT_KEY = "checkpoint"
    }
}

fun ProjectEntity.toSyncProject(): SyncProject = SyncProject(
    uuid = uuid,
    name = name,
    nameNormalized = nameNormalized,
    createdAt = createdAt,
    updatedAt = updatedAt,
    archivedAt = archivedAt,
    syncRevision = syncRevision,
    syncDirty = syncDirty,
)

fun TaskEntity.toSyncTask(): SyncTask = SyncTask(
    uuid = uuid,
    projectUuid = projectUuid,
    sortOrder = sortOrder.toLong(),
    title = title,
    notes = notes,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    syncRevision = syncRevision,
    syncDirty = syncDirty,
)

fun SyncTombstoneEntity.toSyncTombstone(): SyncTombstone = SyncTombstone(
    entityType = entityType,
    uuid = uuid,
    deletedAt = deletedAt,
    syncRevision = syncRevision,
    syncDirty = syncDirty,
)
