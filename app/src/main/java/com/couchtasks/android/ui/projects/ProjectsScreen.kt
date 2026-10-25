package com.couchtasks.android.ui.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.couchtasks.android.data.ProjectWithCounts
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.navigation.Routes
import com.couchtasks.android.viewmodel.ProjectsViewModel

private fun ProjectWithCounts.isInbox() = name.equals(TasksRepository.INBOX_NAME, ignoreCase = true)

private fun ProjectWithCounts.isEmpty() = pendingCount == 0 && doneCount == 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    repository: TasksRepository,
    projectsViewModel: ProjectsViewModel,
    navController: NavController,
) {
    val projects by repository.observeProjectsWithCounts().collectAsStateWithLifecycle(emptyList())
    var showCreateProject by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showDeleteSelected by remember { mutableStateOf(false) }
    val selectionMode = selected.isNotEmpty()
    val emptyProjectUuids = projects.filter { it.isEmpty() && !it.isInbox() }.map { it.uuid }.toSet()

    LaunchedEffect(projects) {
        val existing = projects.map { it.uuid }.toSet()
        if (!existing.containsAll(selected)) selected = selected.intersect(existing)
    }
    BackHandler(enabled = selectionMode) { selected = emptySet() }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        TextButton(onClick = { selected = emptySet() }) { Text("Cancel") }
                    },
                    actions = {
                        TextButton(
                            onClick = { selected = emptyProjectUuids },
                            enabled = emptyProjectUuids.isNotEmpty(),
                        ) { Text("Select empty") }
                        TextButton(onClick = { showDeleteSelected = true }) { Text("Delete") }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(onClick = { showCreateProject = true }) { Text("+") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(projects, key = { it.uuid }) { project ->
                val selectable = !project.isInbox()
                fun toggle() {
                    if (selectable) {
                        selected = if (project.uuid in selected) selected - project.uuid else selected + project.uuid
                    }
                }
                ProjectCard(
                    project = project,
                    selectionMode = selectionMode,
                    selected = project.uuid in selected,
                    selectable = selectable,
                    onClick = {
                        if (selectionMode) toggle() else navController.navigate(Routes.projectTasks(project.uuid))
                    },
                    onLongClick = { if (!selectionMode) toggle() },
                )
            }
        }
    }

    if (showCreateProject) {
        ProjectNameDialog(
            title = "New Project",
            onDismiss = { showCreateProject = false },
            onSave = { name ->
                projectsViewModel.createProject(name) { showCreateProject = false }
            },
        )
    }

    if (showDeleteSelected) {
        val taskCount = projects.filter { it.uuid in selected }.sumOf { it.pendingCount + it.doneCount }
        val projectLabel = if (selected.size == 1) "1 project" else "${selected.size} projects"
        AlertDialog(
            onDismissRequest = { showDeleteSelected = false },
            title = { Text("Delete Projects") },
            text = {
                Text(
                    "Delete $projectLabel and $taskCount ${if (taskCount == 1) "task" else "tasks"}? " +
                        "This creates sync tombstones.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = selected
                        showDeleteSelected = false
                        projectsViewModel.deleteProjects(toDelete) { selected = emptySet() }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelected = false }) { Text("Cancel") }
            },
        )
    }
}
