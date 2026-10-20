package com.couchtasks.android.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "connection_profiles")
data class ConnectionProfile(@PrimaryKey val id: String, val settings: String)

@Entity(tableName = "active_profile")
data class ActiveProfile(@PrimaryKey val id: Int = 1, val profileId: String)

@Entity(tableName = "switch_journal")
data class SwitchJournal(
    @PrimaryKey val id: Int = 1,
    val profileId: String,
    val mode: String,
    val phase: String = "fetch",
    val request: String = "",
    val projectsAdded: Int = 0,
    val tasksCopied: Int = 0,
    val tasksSkipped: Int = 0,
)

@Dao
interface ConnectionDao {
    @Query("SELECT p.* FROM connection_profiles p JOIN active_profile a ON p.id = a.profileId WHERE a.id = 1")
    fun observeActive(): Flow<ConnectionProfile?>
    @Query("SELECT p.* FROM connection_profiles p JOIN active_profile a ON p.id = a.profileId WHERE a.id = 1")
    suspend fun active(): ConnectionProfile?
    @Query("SELECT * FROM connection_profiles WHERE id = :id")
    suspend fun profile(id: String): ConnectionProfile?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(profile: ConnectionProfile)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun activate(active: ActiveProfile)
    @Query("SELECT * FROM switch_journal WHERE id = 1")
    suspend fun journal(): SwitchJournal?
    @Query("SELECT * FROM switch_journal WHERE id = 1")
    fun observeJournal(): Flow<SwitchJournal?>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveJournal(journal: SwitchJournal)
    @Query("DELETE FROM switch_journal")
    suspend fun clearJournal()
    @Query("SELECT * FROM connection_profiles WHERE id NOT IN (SELECT profileId FROM active_profile) AND id NOT IN (SELECT profileId FROM switch_journal)")
    suspend fun unused(): List<ConnectionProfile>
    @Query("DELETE FROM connection_profiles WHERE id = :id")
    suspend fun delete(id: String)
}

suspend fun CouchTasksDatabase.requireNotSwitching() {
    check(connectionDao().journal() == null) { "Server switch in progress. Resume or abandon it in Settings; adding and editing are temporarily unavailable." }
}
