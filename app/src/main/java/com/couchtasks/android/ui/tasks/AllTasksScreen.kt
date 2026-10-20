package com.couchtasks.android.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.couchtasks.android.data.ProjectWithCounts
import com.couchtasks.android.data.TaskStatus
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.navigation.Routes
import com.couchtasks.android.viewmodel.ProjectsViewModel
import com.couchtasks.android.viewmodel.TasksViewModel

@Composable
fun AllTasksScreen(
    repository: TasksRepository,
    tasksViewModel: TasksViewModel,
    projectsViewModel: ProjectsViewModel,
    navController: NavController,
) {
    val projects by repository.observeProjectsWithCounts().collectAsStateWithLifecycle(emptyList())
    val tasks by repository.observeAllTasks().collectAsStateWithLifecycle(emptyList())
    var showAddTask by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddTask = true }) { Text("+") }
        },
    ) { padding ->
        val pendingTasksByProject = tasks
            .filter { it.status == TaskStatus.Pending }
            .groupBy { it.projectUuid }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            projects.forEach { project ->
                val projectTasks = pendingTasksByProject[project.uuid].orEmpty()
                if (projectTasks.isEmpty()) return@forEach
                item(key = "project-${project.uuid}") {
                    ProjectTasksHeader(project)
                }
                items(projectTasks, key = { it.uuid }) { task ->
                    TaskRow(
                        task = task,
                        checked = false,
                        onClick = { navController.navigate(Routes.taskEditor(task.projectUuid, task.uuid)) },
                        onCheckedChange = { checked ->
                            if (checked) tasksViewModel.completeTask(task.uuid) else tasksViewModel.reopenTask(task.uuid)
                        },
                    )
                }
            }
        }
    }

    if (showAddTask) {
        AddTaskDialog(
            tasksViewModel = tasksViewModel,
            projectsViewModel = projectsViewModel,
            projects = projects,
            onDismiss = { showAddTask = false },
            onSaved = { showAddTask = false },
        )
    }
}

@Composable
private fun ProjectTasksHeader(project: ProjectWithCounts) {
    Column(
        modifier = Modifier
            .padding(start = 8.dp, top = 12.dp, end = 8.dp),
    ) {
        Text(project.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}
