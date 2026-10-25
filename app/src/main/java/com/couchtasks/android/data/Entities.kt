package com.couchtasks.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "projects",
    primaryKeys = ["uuid"],
    indices = [
        Index(value = ["name_normalized"], unique = true),
        Index(value = ["sync_dirty"]),
    ],
)
data class ProjectEntity(
    val uuid: String,
    val name: String,
    @ColumnInfo(name = "name_normalized") val nameNormalized: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "archived_at") val archivedAt: String? = null,
    @ColumnInfo(name = "sync_revision") val syncRevision: Long = 0,
    @ColumnInfo(name = "sync_dirty") val syncDirty: Boolean = true,
)

@Entity(
    tableName = "tasks",
    primaryKeys = ["uuid"],
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["project_uuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["project_uuid"]),
        Index(value = ["project_uuid", "status", "sort_order"]),
        Index(value = ["sync_dirty"]),
    ],
)
data class TaskEntity(
    val uuid: String,
    @ColumnInfo(name = "project_uuid") val projectUuid: String,
    val title: String,
    val notes: String = "",
    val status: String = TaskStatus.Pending,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "completed_at") val completedAt: String? = null,
    @ColumnInfo(name = "sync_revision") val syncRevision: Long = 0,
    @ColumnInfo(name = "sync_dirty") val syncDirty: Boolean = true,
    @ColumnInfo(name = "reviewed_at", defaultValue = "") val reviewedAt: String,
)

@Entity(
    tableName = "sync_tombstones",
    primaryKeys = ["entity_type", "uuid"],
    indices = [Index(value = ["sync_dirty"])],
)
data class SyncTombstoneEntity(
    @ColumnInfo(name = "entity_type") val entityType: String,
    val uuid: String,
    @ColumnInfo(name = "sync_revision") val syncRevision: Long = 0,
    @ColumnInfo(name = "deleted_at") val deletedAt: String,
    @ColumnInfo(name = "sync_dirty") val syncDirty: Boolean = true,
)

@Entity(tableName = "sync_state", primaryKeys = ["key"])
data class SyncStateEntity(
    val key: String,
    val value: String,
)

/** The one durable, blocking sync problem. A successful sync deletes this row. */
@Entity(tableName = "sync_issue")
data class SyncIssueEntity(
    @androidx.room.PrimaryKey val id: Int = SINGLETON_ID,
    val kind: String,
    @ColumnInfo(name = "http_status") val httpStatus: Int? = null,
    val detail: String,
    @ColumnInfo(name = "request_checkpoint") val requestCheckpoint: Long,
    @ColumnInfo(name = "entity_uuid") val entityUuid: String? = null,
    @ColumnInfo(name = "related_uuid") val relatedUuid: String? = null,
    @ColumnInfo(name = "server_checkpoint") val serverCheckpoint: Long? = null,
    @ColumnInfo(name = "detected_at") val detectedAt: String,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

data class ProjectWithCounts(
    val uuid: String,
    val name: String,
    val pendingCount: Int,
    val doneCount: Int,
)

data class ReviewProject(
    val uuid: String,
    val name: String,
    val isInbox: Boolean,
)
