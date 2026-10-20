package com.couchtasks.android.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.settings.CertificatePasswordStore
import com.couchtasks.android.settings.SettingsRepository
import com.couchtasks.android.sync.ConfiguredSyncRunner
import com.couchtasks.android.ui.projects.ProjectsScreen
import com.couchtasks.android.ui.settings.SettingsScreen
import com.couchtasks.android.ui.sync.SyncIssueScreen
import com.couchtasks.android.ui.tasks.AllTasksScreen
import com.couchtasks.android.ui.tasks.InboxTasksScreen
import com.couchtasks.android.ui.tasks.ProjectTasksScreen
import com.couchtasks.android.ui.tasks.TaskEditorScreen
import com.couchtasks.android.viewmodel.ProjectsViewModel
import com.couchtasks.android.viewmodel.SettingsViewModel
import com.couchtasks.android.viewmodel.TasksViewModel
import com.couchtasks.android.viewmodel.SyncIssueViewModel
import kotlinx.coroutines.flow.StateFlow

@Composable
fun CouchTasksNavHost(
    repository: TasksRepository,
    settingsRepository: SettingsRepository,
    certificatePasswordStore: CertificatePasswordStore,
    configuredSyncRunner: ConfiguredSyncRunner,
    switchCoordinator: com.couchtasks.android.sync.ServerSwitchCoordinator? = null,
    onLocalChange: () -> Unit = {},
    openTaskRequest: StateFlow<String?>?,
    onOpenTaskRequestHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val tasksViewModel: TasksViewModel = viewModel(
        key = "tasks",
        factory = viewModelFactory { initializer { TasksViewModel(repository, onLocalChange) } },
    )
    val projectsViewModel: ProjectsViewModel = viewModel(
        key = "projects",
        factory = viewModelFactory { initializer { ProjectsViewModel(repository, onLocalChange) } },
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        key = "settings",
        factory = viewModelFactory {
            initializer { SettingsViewModel(settingsRepository, certificatePasswordStore, configuredSyncRunner, switchCoordinator) }
        },
    )
    val syncIssueViewModel: SyncIssueViewModel = viewModel(
        key = "sync-issue",
        factory = viewModelFactory {
            initializer { SyncIssueViewModel(repository, settingsRepository, configuredSyncRunner) }
        },
    )
    val pendingSwitch by settingsViewModel.switchJournal.collectAsStateWithLifecycle(null)
    LaunchedEffect(pendingSwitch?.profileId) {
        if (pendingSwitch != null) navController.navigate(Routes.Settings) { launchSingleTop = true }
    }
    val syncIssue by syncIssueViewModel.issue.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(repository) { runCatching { repository.ensureInbox() } }
    LaunchedEffect(openTaskRequest, repository, navController) {
        openTaskRequest?.collect { taskUuid ->
            if (taskUuid.isNullOrBlank()) return@collect
            repository.getTask(taskUuid)?.let { task ->
                navController.navigate(Routes.taskEditor(task.projectUuid, task.uuid)) {
                    launchSingleTop = true
                }
            }
            onOpenTaskRequestHandled()
        }
    }
    LaunchedEffect(tasksViewModel) {
        tasksViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(projectsViewModel) {
        projectsViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(settingsViewModel) {
        settingsViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(syncIssueViewModel) {
        syncIssueViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            if (syncIssue != null) {
                Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Routes.SyncIssue) { launchSingleTop = true } }
                        .testTag("SyncIssueBanner"),
                ) {
                    Text("Sync needs attention", Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        },
        bottomBar = { CouchTasksBottomBar(navController) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.AllTasks,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.AllTasks) {
                AllTasksScreen(repository, tasksViewModel, projectsViewModel, navController)
            }
            composable(Routes.Projects) {
                ProjectsScreen(repository, projectsViewModel, navController)
            }
            composable(Routes.Tasks) {
                InboxTasksScreen(repository, tasksViewModel, navController)
            }
            composable(Routes.Settings) {
                SettingsScreen(settingsViewModel)
            }
            composable(Routes.SyncIssue) {
                SyncIssueScreen(
                    repository = repository,
                    viewModel = syncIssueViewModel,
                    onCancel = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                    onOpenProjects = { navController.navigate(Routes.Projects) },
                )
            }
            composable(
                route = Routes.ProjectTasks,
                arguments = listOf(navArgument("projectUuid") { type = NavType.StringType }),
            ) { entry ->
                val projectUuid = checkNotNull(entry.arguments?.getString("projectUuid"))
                ProjectTasksScreen(
                    repository = repository,
                    tasksViewModel = tasksViewModel,
                    projectUuid = projectUuid,
                    navController = navController,
                    onBack = { navController.popBackStack() },
                    projectsViewModel = projectsViewModel,
                    onProjectDeleted = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.TaskEditor,
                arguments = listOf(
                    navArgument("projectUuid") { type = NavType.StringType },
                    navArgument("taskUuid") { type = NavType.StringType },
                ),
            ) { entry ->
                TaskEditorScreen(
                    repository = repository,
                    tasksViewModel = tasksViewModel,
                    initialProjectUuid = checkNotNull(entry.arguments?.getString("projectUuid")),
                    taskUuid = checkNotNull(entry.arguments?.getString("taskUuid")),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
