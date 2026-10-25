package com.couchtasks.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query(
        """
        SELECT p.uuid, p.name,
            (SELECT COUNT(*) FROM tasks t WHERE t.project_uuid = p.uuid AND t.status = 'pending') AS pendingCount,
            (SELECT COUNT(*) FROM tasks t WHERE t.project_uuid = p.uuid AND t.status = 'done') AS doneCount
        FROM projects p
        ORDER BY
            CASE
                WHEN p.name_normalized = 'inbox' THEN 0
                WHEN pendingCount > 0 THEN 1
                ELSE 2
            END,
            p.name COLLATE NOCASE
        """,
    )
    fun observeProjectsWithCounts(): Flow<List<ProjectWithCounts>>

    /** Projects with a pending task reviewed at or before [cutoff]; Inbox counts whenever it has a pending task. */
    @Query(
        """
        SELECT p.uuid, p.name, (p.name_normalized = 'inbox') AS isInbox
        FROM projects p
        WHERE EXISTS (
            SELECT 1 FROM tasks t
            WHERE t.project_uuid = p.uuid AND t.status = 'pending'
                AND (p.name_normalized = 'inbox' OR t.reviewed_at <= :cutoff)
        )
        ORDER BY CASE WHEN p.name_normalized = 'inbox' THEN 0 ELSE 1 END, p.name COLLATE NOCASE
        """,
    )
    fun observeReviewQueue(cutoff: String): Flow<List<ReviewProject>>

    @Query("SELECT * FROM projects WHERE uuid = :uuid")
    suspend fun get(uuid: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE name_normalized = :nameNormalized")
    suspend fun getByNormalizedName(nameNormalized: String): ProjectEntity?

    @Query("SELECT * FROM projects ORDER BY name_normalized ASC, uuid ASC")
    suspend fun allProjects(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE sync_dirty = 1")
    suspend fun dirtyProjects(): List<ProjectEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: ProjectEntity)

    @androidx.room.Transaction
    suspend fun upsert(project: ProjectEntity) {
        if (get(project.uuid) == null) insert(project) else update(project)
    }

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("UPDATE projects SET name_normalized = :temporaryNameNormalized WHERE uuid = :uuid")
    suspend fun setTemporaryNormalizedName(uuid: String, temporaryNameNormalized: String)

    @Query("DELETE FROM projects WHERE uuid = :uuid")
    suspend fun delete(uuid: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE project_uuid = :projectUuid AND status = :status ORDER BY sort_order ASC, created_at ASC")
    fun observeProjectTasksByStatus(projectUuid: String, status: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE project_uuid = :projectUuid AND status = 'done' ORDER BY completed_at DESC, updated_at DESC")
    fun observeDoneProjectTasks(projectUuid: String): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        ORDER BY project_uuid ASC,
            CASE WHEN status = 'pending' THEN 0 ELSE 1 END,
            sort_order ASC,
            completed_at DESC,
            created_at ASC
        """,
    )
    fun observeAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE project_uuid = :projectUuid AND status = 'pending' ORDER BY sort_order ASC, created_at ASC, uuid ASC")
    suspend fun pendingTasks(projectUuid: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE project_uuid = :projectUuid")
    suspend fun tasksForProject(projectUuid: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE project_uuid = :projectUuid AND status = 'done' ORDER BY completed_at DESC, updated_at DESC")
    suspend fun doneTasks(projectUuid: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE uuid = :uuid")
    suspend fun get(uuid: String): TaskEntity?

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM tasks WHERE project_uuid = :projectUuid AND status = 'pending'")
    suspend fun maxPendingSortOrder(projectUuid: String): Int

    @Query("SELECT * FROM tasks ORDER BY project_uuid ASC, status ASC, sort_order ASC, uuid ASC")
    suspend fun allTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE sync_dirty = 1")
    suspend fun dirtyTasks(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE uuid = :uuid")
    suspend fun delete(uuid: String)

    @Query("UPDATE tasks SET project_uuid = :newProjectUuid, sync_dirty = 1 WHERE project_uuid = :oldProjectUuid")
    suspend fun moveToProject(oldProjectUuid: String, newProjectUuid: String)

    @Query("UPDATE tasks SET reviewed_at = :reviewedAt, sync_dirty = 1 WHERE project_uuid = :projectUuid AND status = 'pending'")
    suspend fun markPendingReviewed(projectUuid: String, reviewedAt: String)

    @Query("DELETE FROM tasks WHERE project_uuid = :projectUuid")
    suspend fun deleteForProject(projectUuid: String)
}

@Dao
interface TombstoneDao {
    @Query("SELECT * FROM sync_tombstones WHERE entity_type = :entityType AND uuid = :uuid")
    suspend fun get(entityType: String, uuid: String): SyncTombstoneEntity?

    @Query("SELECT * FROM sync_tombstones ORDER BY entity_type ASC, uuid ASC")
    suspend fun allTombstones(): List<SyncTombstoneEntity>

    @Query("SELECT * FROM sync_tombstones WHERE sync_dirty = 1")
    suspend fun dirtyTombstones(): List<SyncTombstoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tombstone: SyncTombstoneEntity)
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(state: SyncStateEntity)
}

@Dao
interface SyncIssueDao {
    @Query("SELECT * FROM sync_issue WHERE id = 1")
    fun observe(): Flow<SyncIssueEntity?>

    @Query("SELECT * FROM sync_issue WHERE id = 1")
    suspend fun get(): SyncIssueEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(issue: SyncIssueEntity)

    @Query("DELETE FROM sync_issue")
    suspend fun clear()
}
