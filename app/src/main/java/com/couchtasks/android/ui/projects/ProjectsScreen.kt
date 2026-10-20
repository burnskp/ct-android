package com.couchtasks.android.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.navigation.Routes
import com.couchtasks.android.viewmodel.ProjectsViewModel

@Composable
fun ProjectsScreen(
    repository: TasksRepository,
    projectsViewModel: ProjectsViewModel,
    navController: NavController,
) {
    val projects by repository.observeProjectsWithCounts().collectAsStateWithLifecycle(emptyList())
    var showCreateProject by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateProject = true }) { Text("+") }
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
                ProjectCard(
                    project = project,
                    onClick = { navController.navigate(Routes.projectTasks(project.uuid)) },
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
}
