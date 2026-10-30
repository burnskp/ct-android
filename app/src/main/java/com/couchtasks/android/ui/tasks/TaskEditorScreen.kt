package com.couchtasks.android.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couchtasks.android.data.TaskEntity
import com.couchtasks.android.data.TaskStatus
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.navigation.Routes
import com.couchtasks.android.ui.common.EmptyScreen
import com.couchtasks.android.viewmodel.TasksViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(
    repository: TasksRepository,
    tasksViewModel: TasksViewModel,
    initialProjectUuid: String,
    taskUuid: String,
    onBack: () -> Unit,
    initialTitle: String = "",
    initialNotes: String = "",
) {
    val projects by repository.observeProjectsWithCounts().collectAsStateWithLifecycle(emptyList())
    val isNew = taskUuid == Routes.NewTaskUuid
    var selectedProjectUuid by remember(initialProjectUuid) { mutableStateOf(initialProjectUuid) }
    var title by remember(taskUuid) { mutableStateOf(initialTitle) }
    var notes by remember(taskUuid) { mutableStateOf(initialNotes) }
    var status by remember(taskUuid) { mutableStateOf(TaskStatus.Pending) }
    var loadedTask by remember(taskUuid) { mutableStateOf<TaskEntity?>(null) }
    var finishedLoading by remember(taskUuid) { mutableStateOf(isNew) }
    var attemptedSave by remember(taskUuid) { mutableStateOf(false) }
    var projectMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(taskUuid) {
        if (!isNew) {
            loadedTask = repository.getTask(taskUuid)
            finishedLoading = true
            loadedTask?.let { task ->
                selectedProjectUuid = task.projectUuid
                title = task.title
                notes = task.notes
                status = task.status
            }
        }
    }

    val selectedProjectName = projects.firstOrNull { it.uuid == selectedProjectUuid }?.name ?: "Select project"
    val titleIsBlank = title.trim().isEmpty()

    fun saveTask() {
        attemptedSave = true
        if (title.trim().isEmpty() || selectedProjectUuid.isBlank()) return
        if (isNew) {
            tasksViewModel.createTask(
                projectUuid = selectedProjectUuid,
                title = title,
                notes = notes,
                status = status,
                onSuccess = onBack,
            )
        } else {
            tasksViewModel.editTask(
                taskUuid = taskUuid,
                title = title,
                notes = notes,
                projectUuid = selectedProjectUuid,
                status = status,
                onSuccess = onBack,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New Task" else "Edit Task") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } },
                actions = {
                    TextButton(onClick = { saveTask() }) { Text("✓") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (!finishedLoading) {
            EmptyScreen("Loading task…")
            return@Scaffold
        }
        if (!isNew && loadedTask == null) {
            EmptyScreen("Task not found")
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                isError = attemptedSave && titleIsBlank,
                supportingText = {
                    if (attemptedSave && titleIsBlank) Text("Title is required")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveTask() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("TaskTitleField"),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            Box {
                Button(onClick = { projectMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Project: $selectedProjectName")
                }
                DropdownMenu(
                    expanded = projectMenuExpanded,
                    onDismissRequest = { projectMenuExpanded = false },
                ) {
                    projects.forEach { project ->
                        DropdownMenuItem(
                            text = { Text(project.name) },
                            onClick = {
                                selectedProjectUuid = project.uuid
                                projectMenuExpanded = false
                            },
                        )
                    }
                }
            }
            Text("Status", fontWeight = FontWeight.SemiBold)
            PrimaryTabRow(selectedTabIndex = if (status == TaskStatus.Pending) 0 else 1) {
                Tab(
                    selected = status == TaskStatus.Pending,
                    onClick = { status = TaskStatus.Pending },
                    text = { Text("Pending") },
                )
                Tab(
                    selected = status == TaskStatus.Done,
                    onClick = { status = TaskStatus.Done },
                    text = { Text("Done") },
                )
            }
        }
    }
}
