package com.couchtasks.android.ui.tasks

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.couchtasks.android.data.TaskEntity
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.navigation.Routes
import com.couchtasks.android.ui.common.EmptyScreen
import com.couchtasks.android.ui.projects.ProjectNameDialog
import com.couchtasks.android.viewmodel.ProjectsViewModel
import com.couchtasks.android.viewmodel.TasksViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectTasksScreen(
    repository: TasksRepository,
    tasksViewModel: TasksViewModel,
    projectUuid: String,
    navController: NavController,
    onBack: (() -> Unit)?,
    projectsViewModel: ProjectsViewModel? = null,
    onProjectDeleted: () -> Unit = {},
) {
    var projectName by remember(projectUuid) { mutableStateOf("Tasks") }
    LaunchedEffect(projectUuid) { projectName = repository.getProject(projectUuid)?.name ?: "Tasks" }
    val pending by repository.observePendingTasks(projectUuid).collectAsStateWithLifecycle(emptyList())
    val done by repository.observeDoneTasks(projectUuid).collectAsStateWithLifecycle(emptyList())
    var selectedTab by remember { mutableIntStateOf(0) }
    var confirmClearDone by remember { mutableStateOf(false) }
    var projectMenuExpanded by remember { mutableStateOf(false) }
    var showRenameProject by remember { mutableStateOf(false) }
    var confirmDeleteProject by remember { mutableStateOf(false) }
    var visualPending by remember { mutableStateOf<List<TaskEntity>>(emptyList()) }
    var draggedTaskUuid by remember { mutableStateOf<String?>(null) }
    var draggedPointerY by remember { mutableFloatStateOf(0f) }
    val rowBounds = remember { mutableStateMapOf<String, Rect>() }

    LaunchedEffect(pending, selectedTab) {
        if (draggedTaskUuid == null) visualPending = pending
    }

    fun resetDrag() {
        draggedTaskUuid = null
        draggedPointerY = 0f
        visualPending = pending
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = projectName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (projectsViewModel != null) {
                            IconButton(
                                onClick = { projectMenuExpanded = true },
                                modifier = Modifier.semantics {
                                    contentDescription = "Project actions for $projectName"
                                },
                            ) {
                                Text("☰")
                            }
                            DropdownMenu(
                                expanded = projectMenuExpanded,
                                onDismissRequest = { projectMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        projectMenuExpanded = false
                                        showRenameProject = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        projectMenuExpanded = false
                                        confirmDeleteProject = true
                                    },
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) TextButton(onClick = onBack) { Text("‹") }
                },
                actions = {
                    if (selectedTab == 1 && done.isNotEmpty()) {
                        TextButton(onClick = { confirmClearDone = true }) { Text("Clear Done") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate(Routes.taskEditor(projectUuid)) }) { Text("+") }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Pending") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Done") })
            }
            val isPendingTab = selectedTab == 0
            val visibleTasks = if (isPendingTab) {
                if (draggedTaskUuid == null) pending else visualPending
            } else {
                done
            }
            if (visibleTasks.isEmpty()) {
                EmptyScreen(if (selectedTab == 0) "No pending tasks" else "No done tasks")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visibleTasks, key = { task -> task.uuid }) { task ->
                        val draggedUuid = draggedTaskUuid
                        val rowModifier = if (isPendingTab) {
                            Modifier
                                .onGloballyPositioned { coordinates ->
                                    rowBounds[task.uuid] = coordinates.boundsInRoot()
                                }
                                .then(
                                    if (draggedUuid == task.uuid) {
                                        val centerY = rowBounds[task.uuid]?.center?.y ?: draggedPointerY
                                        Modifier
                                            .zIndex(1f)
                                            .offset { IntOffset(x = 0, y = (draggedPointerY - centerY).roundToInt()) }
                                    } else {
                                        Modifier
                                    },
                                )
                                .pointerInput(task.uuid, selectedTab) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            visualPending = pending
                                            draggedTaskUuid = task.uuid
                                            draggedPointerY = (rowBounds[task.uuid]?.top ?: 0f) + offset.y
                                        },
                                        onDragCancel = { resetDrag() },
                                        onDragEnd = {
                                            val orderedUuids = visualPending.map { it.uuid }
                                            if (orderedUuids != pending.map { it.uuid }) {
                                                tasksViewModel.reorderPendingTasks(projectUuid, orderedUuids)
                                            }
                                            draggedTaskUuid = null
                                            draggedPointerY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            draggedPointerY += dragAmount.y
                                            val draggingUuid = draggedTaskUuid ?: return@detectDragGesturesAfterLongPress
                                            val fromIndex = visualPending.indexOfFirst { it.uuid == draggingUuid }
                                            if (fromIndex == -1) return@detectDragGesturesAfterLongPress

                                            val toIndex = visualPending
                                                .filterNot { it.uuid == draggingUuid }
                                                .count { (rowBounds[it.uuid]?.center?.y ?: Float.MAX_VALUE) < draggedPointerY }
                                                .coerceIn(0, visualPending.lastIndex)

                                            if (toIndex != fromIndex) {
                                                visualPending = visualPending.move(fromIndex, toIndex)
                                            }
                                        },
                                    )
                                }
                        } else {
                            Modifier
                        }

                        TaskRow(
                            task = task,
                            checked = selectedTab == 1,
                            onClick = { navController.navigate(Routes.taskEditor(projectUuid, task.uuid)) },
                            onCheckedChange = { checked ->
                                if (checked) tasksViewModel.completeTask(task.uuid) else tasksViewModel.reopenTask(task.uuid)
                            },
                            modifier = rowModifier,
                        )
                    }
                }
            }
        }
    }

    if (confirmClearDone) {
        AlertDialog(
            onDismissRequest = { confirmClearDone = false },
            title = { Text("Clear Done") },
            text = { Text("Delete all done tasks in $projectName? This creates sync tombstones.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        tasksViewModel.clearDoneTasks(projectUuid) { confirmClearDone = false }
                    },
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClearDone = false }) { Text("Cancel") } },
        )
    }

    if (showRenameProject && projectsViewModel != null) {
        ProjectNameDialog(
            title = "Rename Project",
            initialName = projectName,
            onDismiss = { showRenameProject = false },
            onSave = { name ->
                projectsViewModel.renameProject(projectUuid, name) {
                    projectName = name.trim()
                    showRenameProject = false
                }
            },
        )
    }

    if (confirmDeleteProject && projectsViewModel != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteProject = false },
            title = { Text("Delete Project") },
            text = { Text("Delete $projectName and its tasks? This creates a sync tombstone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        projectsViewModel.deleteProject(projectUuid) {
                            confirmDeleteProject = false
                            onProjectDeleted()
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteProject = false }) { Text("Cancel") } },
        )
    }
}

private fun List<TaskEntity>.move(fromIndex: Int, toIndex: Int): List<TaskEntity> =
    toMutableList().also { tasks -> tasks.add(toIndex, tasks.removeAt(fromIndex)) }
