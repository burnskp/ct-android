package com.couchtasks.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.settings.SettingsRepository
import com.couchtasks.android.sync.ConfiguredSyncRunner
import com.couchtasks.android.sync.buildRedactedSyncDiagnostics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SyncIssueViewModel(
    private val repository: TasksRepository,
    private val settingsRepository: SettingsRepository,
    private val runner: ConfiguredSyncRunner,
) : ViewModel() {
    val issue = repository.observeSyncIssue()
    val projects = repository.observeProjectsWithCounts()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    suspend fun diagnostics(): String? {
        val issue = issue.first() ?: return null
        return buildRedactedSyncDiagnostics(issue, settingsRepository.currentState().serverUrl)
    }

    fun retry() = launchRepair { runner.syncNow() }

    fun renameProject(uuid: String, name: String, onCommitted: () -> Unit = {}) = launchRepair {
        repository.renameProject(uuid, name)
        onCommitted()
        runner.syncNow()
    }

    fun moveTask(taskUuid: String, projectUuid: String, onCommitted: () -> Unit = {}) = launchRepair {
        repository.moveOrphanTask(taskUuid, projectUuid)
        onCommitted()
        runner.syncNow()
    }

    fun deleteTask(taskUuid: String, onCommitted: () -> Unit = {}) = launchRepair {
        repository.deleteTask(taskUuid)
        onCommitted()
        runner.syncNow()
    }

    private fun launchRepair(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { _messages.emit(it.userVisibleMessage()) }
        }
    }
}
