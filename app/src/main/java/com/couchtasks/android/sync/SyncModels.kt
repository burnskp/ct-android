package com.couchtasks.android.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val SyncJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

@Serializable
data class SyncRequest(
    @SerialName("checkpoint") val checkpoint: Long = 0,
    @SerialName("projects") val projects: List<SyncProject> = emptyList(),
    @SerialName("tasks") val tasks: List<SyncTask> = emptyList(),
    @SerialName("tombstones") val tombstones: List<SyncTombstone> = emptyList(),
)

@Serializable
data class SyncResponse(
    @SerialName("checkpoint") val checkpoint: Long,
    @SerialName("projects") val projects: List<SyncProject> = emptyList(),
    @SerialName("tasks") val tasks: List<SyncTask> = emptyList(),
    @SerialName("tombstones") val tombstones: List<SyncTombstone> = emptyList(),
)

@Serializable
data class SyncProject(
    @SerialName("uuid") val uuid: String,
    @SerialName("name") val name: String,
    @SerialName("name_normalized") val nameNormalized: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("sync_revision") val syncRevision: Long = 0,
    @SerialName("sync_dirty") val syncDirty: Boolean = true,
)

@Serializable
data class SyncTask(
    @SerialName("uuid") val uuid: String,
    @SerialName("project_uuid") val projectUuid: String,
    @SerialName("sort_order") val sortOrder: Long = 0,
    @SerialName("title") val title: String,
    @SerialName("notes") val notes: String = "",
    @SerialName("status") val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("sync_revision") val syncRevision: Long = 0,
    @SerialName("sync_dirty") val syncDirty: Boolean = true,
    /** Required by v4 servers; null only when decoding a response from an older server. */
    @SerialName("reviewed_at") val reviewedAt: String? = null,
)

@Serializable
data class SyncTombstone(
    @SerialName("entity_type") val entityType: String,
    @SerialName("uuid") val uuid: String,
    @SerialName("deleted_at") val deletedAt: String,
    @SerialName("sync_revision") val syncRevision: Long = 0,
    @SerialName("sync_dirty") val syncDirty: Boolean = true,
)
