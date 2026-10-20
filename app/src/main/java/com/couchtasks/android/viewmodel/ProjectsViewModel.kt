package com.couchtasks.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couchtasks.android.data.ProjectEntity
import com.couchtasks.android.data.TasksRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class ProjectsViewModel(
    private val repository: TasksRepository,
    private val onLocalChange: () -> Unit,
) : ViewModel() {
    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun createProject(name: String, onSuccess: (ProjectEntity) -> Unit = {}) {
        launchProjectAction {
            repository.createProject(name).also(onSuccess)
        }
    }

    fun renameProject(projectUuid: String, name: String, onSuccess: () -> Unit = {}) {
        launchProjectAction {
            repository.renameProject(projectUuid, name)
            onSuccess()
        }
    }

    fun deleteProject(projectUuid: String, onSuccess: () -> Unit = {}) {
        launchProjectAction {
            repository.deleteProject(projectUuid)
            onSuccess()
        }
    }

    private fun launchProjectAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { onLocalChange() }
                .onFailure { _messages.emit(it.userVisibleMessage()) }
        }
    }
}
