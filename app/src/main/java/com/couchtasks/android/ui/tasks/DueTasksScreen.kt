package com.couchtasks.android.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.couchtasks.android.data.DueTask
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.navigation.Routes
import com.couchtasks.android.ui.common.EmptyScreen
import com.couchtasks.android.viewmodel.TasksViewModel

/** Pending tasks with a due date across all projects, soonest first (`ct list --due`). */
@Composable
fun DueTasksScreen(
    repository: TasksRepository,
    tasksViewModel: TasksViewModel,
    navController: NavController,
) {
    val dueTasks by repository.observeDueTasks().collectAsStateWithLifecycle(null)
    val loaded = dueTasks ?: return
    if (loaded.isEmpty()) {
        EmptyScreen("No pending tasks have a due date")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(loaded, key = { it.task.uuid }) { (task, projectName): DueTask ->
            TaskRow(
                task = task,
                checked = false,
                projectName = projectName,
                onClick = { navController.navigate(Routes.taskEditor(task.projectUuid, task.uuid)) },
                onCheckedChange = { checked -> if (checked) tasksViewModel.completeTask(task.uuid) },
            )
        }
    }
}
