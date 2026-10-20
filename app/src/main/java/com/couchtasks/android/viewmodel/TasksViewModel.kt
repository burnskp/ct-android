package com.couchtasks.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couchtasks.android.data.TaskStatus
import com.couchtasks.android.data.TasksRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class TasksViewModel(
    private val repository: TasksRepository,
    private val onLocalChange: () -> Unit,
) : ViewModel() {
    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun createTask(
        projectUuid: String,
        title: String,
        notes: String,
        status: String = TaskStatus.Pending,
        onSuccess: () -> Unit = {},
    ) {
        launchTaskAction {
            repository.createTask(
                projectUuid = projectUuid,
                title = title,
                notes = notes,
                status = status,
            )
            onSuccess()
        }
    }

    fun editTask(
        taskUuid: String,
        title: String,
        notes: String,
        projectUuid: String,
        status: String,
        onSuccess: () -> Unit = {},
    ) {
        launchTaskAction {
            repository.editTask(
                taskUuid = taskUuid,
                title = title,
                notes = notes,
                projectUuid = projectUuid,
                status = status,
            )
            onSuccess()
        }
    }

    fun completeTask(taskUuid: String) {
        launchTaskAction { repository.completeTask(taskUuid) }
    }

    fun reopenTask(taskUuid: String) {
        launchTaskAction { repository.reopenTask(taskUuid) }
    }

    fun reorderPendingTasks(projectUuid: String, orderedTaskUuids: List<String>) {
        launchTaskAction { repository.reorderPendingTasks(projectUuid, orderedTaskUuids) }
    }

    fun clearDoneTasks(projectUuid: String, onSuccess: () -> Unit = {}) {
        launchTaskAction {
            repository.clearDoneTasks(projectUuid)
            onSuccess()
        }
    }

    private fun launchTaskAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { onLocalChange() }
                .onFailure { _messages.emit(it.userVisibleMessage()) }
        }
    }
}
