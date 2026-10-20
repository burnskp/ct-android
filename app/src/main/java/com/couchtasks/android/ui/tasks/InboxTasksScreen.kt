package com.couchtasks.android.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.ui.common.EmptyScreen
import com.couchtasks.android.viewmodel.TasksViewModel

@Composable
fun InboxTasksScreen(repository: TasksRepository, tasksViewModel: TasksViewModel, navController: NavController) {
    val projects by repository.observeProjectsWithCounts().collectAsStateWithLifecycle(emptyList())
    val inbox = projects.firstOrNull { it.name.equals("Inbox", ignoreCase = true) } ?: projects.firstOrNull()
    if (inbox == null) {
        EmptyScreen("Preparing Inbox…")
    } else {
        ProjectTasksScreen(repository, tasksViewModel, inbox.uuid, navController = navController, onBack = null)
    }
}
