package com.couchtasks.android.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.couchtasks.android.data.DueDates
import com.couchtasks.android.data.ProjectWithCounts
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.ui.projects.ProjectNameDialog
import com.couchtasks.android.viewmodel.ProjectsViewModel
import com.couchtasks.android.viewmodel.TasksViewModel
import java.time.Instant
import java.time.ZoneId

@Composable
fun AddTaskDialog(
    tasksViewModel: TasksViewModel,
    projectsViewModel: ProjectsViewModel,
    projects: List<ProjectWithCounts>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var selectedProjectUuid by remember { mutableStateOf("") }
    var projectMenuExpanded by remember { mutableStateOf(false) }
    var showCreateProject by remember { mutableStateOf(false) }
    var dueText by remember { mutableStateOf("") }
    var remind by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val remindChange = rememberRemindChange { remind = it }
    val zone = ZoneId.systemDefault()
    val now = LocalNow.current
    val parsedDue = dueText.takeIf(String::isNotBlank)?.let { runCatching { DueDates.parse(it, now, zone) } }
    val dueValid = parsedDue?.isSuccess != false

    LaunchedEffect(projects) {
        if (selectedProjectUuid.isBlank() || projects.none { it.uuid == selectedProjectUuid }) {
            selectedProjectUuid = projects.firstOrNull { it.name.equals(TasksRepository.INBOX_NAME, ignoreCase = true) }?.uuid
                ?: projects.firstOrNull()?.uuid
                ?: ""
        }
    }

    fun saveTask() {
        if (title.trim().isEmpty() || selectedProjectUuid.isBlank()) return
        // Re-parse at save time so relative input (`0d`, `2w`) counts from the moment of saving.
        val dueAt = dueText.takeIf(String::isNotBlank)?.let { runCatching { DueDates.parse(it, Instant.now(), zone) }.getOrNull() ?: return }
        tasksViewModel.createTask(
            projectUuid = selectedProjectUuid,
            title = title,
            notes = notes,
            dueAt = dueAt,
            remind = remind && dueAt != null,
            onSuccess = onSaved,
        )
    }

    val selectedProjectName = projects.firstOrNull { it.uuid == selectedProjectUuid }?.name ?: "Select project"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Task") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { saveTask() }),
                    modifier = Modifier.testTag("AddTaskTitleField"),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    minLines = 3,
                    maxLines = 8,
                )
                OutlinedTextField(
                    value = dueText,
                    onValueChange = { dueText = it },
                    label = { Text("Due (optional)") },
                    placeholder = { Text("15, 12-25, 2w 9:30") },
                    singleLine = true,
                    isError = !dueValid,
                    supportingText = parsedDue?.let { result ->
                        {
                            Text(
                                result.fold({ "→ ${DueDates.formatAbsolute(it, zone)}" }, { it.message.orEmpty() }),
                                modifier = Modifier.testTag("AddTaskDuePreview"),
                            )
                        }
                    },
                    trailingIcon = {
                        TextButton(onClick = { showDatePicker = true }, modifier = Modifier.testTag("AddTaskDuePickerButton")) {
                            Text("📅")
                        }
                    },
                    modifier = Modifier.testTag("AddTaskDueField"),
                )
                // The whole row toggles, so tapping the label works too.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .toggleable(
                            value = remind && parsedDue?.isSuccess == true,
                            enabled = parsedDue?.isSuccess == true,
                            role = Role.Checkbox,
                            onValueChange = remindChange,
                        )
                        .testTag("AddTaskRemindCheckbox"),
                ) {
                    Checkbox(
                        checked = remind && parsedDue?.isSuccess == true,
                        onCheckedChange = null,
                        enabled = parsedDue?.isSuccess == true,
                    )
                    Text("Remind me")
                }
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
                        DropdownMenuItem(
                            text = { Text("New project…") },
                            onClick = {
                                projectMenuExpanded = false
                                showCreateProject = true
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { saveTask() },
                enabled = title.trim().isNotEmpty() && selectedProjectUuid.isNotBlank() && dueValid,
                modifier = Modifier.testTag("AddTaskSaveButton"),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showDatePicker) {
        DueDatePickerDialog(
            initial = parsedDue?.getOrNull()?.let { DueDates.toLocal(it, zone)?.toLocalDate() },
            onConfirm = { date ->
                showDatePicker = false
                // Keep a typed time token; the date picker only replaces the date.
                val time = dueText.trim().split(Regex("""\s+""")).getOrNull(1)
                dueText = listOfNotNull(date.toString(), time).joinToString(" ")
            },
            onDismiss = { showDatePicker = false },
        )
    }

    if (showCreateProject) {
        ProjectNameDialog(
            title = "New Project",
            onDismiss = { showCreateProject = false },
            onSave = { name ->
                projectsViewModel.createProject(name) { project ->
                    selectedProjectUuid = project.uuid
                    showCreateProject = false
                }
            },
        )
    }
}
