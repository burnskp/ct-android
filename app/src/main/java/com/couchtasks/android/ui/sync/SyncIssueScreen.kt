package com.couchtasks.android.ui.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couchtasks.android.data.ProjectEntity
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.sync.SyncIssueKind
import com.couchtasks.android.sync.kindValue
import com.couchtasks.android.ui.projects.ProjectNameDialog
import com.couchtasks.android.viewmodel.SyncIssueViewModel
import kotlinx.coroutines.launch

@Composable
fun SyncIssueScreen(
    repository: TasksRepository,
    viewModel: SyncIssueViewModel,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProjects: () -> Unit,
) {
    val issue by viewModel.issue.collectAsStateWithLifecycle(initialValue = null)
    val projects by viewModel.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val current = issue
    if (current == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Sync is ready", style = MaterialTheme.typography.headlineSmall)
            Text("The blocking sync issue has been resolved.")
            Button(onClick = onCancel) { Text("Done") }
        }
        return
    }

    var entityProject by remember(current.entityUuid) { mutableStateOf<ProjectEntity?>(null) }
    var relatedProject by remember(current.relatedUuid) { mutableStateOf<ProjectEntity?>(null) }
    var taskTitle by remember(current.entityUuid) { mutableStateOf<String?>(null) }
    LaunchedEffect(current.entityUuid, current.relatedUuid) {
        entityProject = current.entityUuid?.let { repository.getProject(it) }
        relatedProject = current.relatedUuid?.let { repository.getProject(it) }
        taskTitle = current.entityUuid?.let { repository.getTask(it)?.title }
    }

    var showRename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kind = current.kindValue()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("SyncIssueScreen"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Sync needs attention", style = MaterialTheme.typography.headlineSmall)
        Text(issueExplanation(kind))

        if (kind == SyncIssueKind.CHECKPOINT_AHEAD) {
            Text("Device checkpoint: ${current.requestCheckpoint}")
            Text("Server checkpoint: ${current.serverCheckpoint ?: "unknown"}")
        }
        if (kind == SyncIssueKind.PROJECT_NAME_COLLISION) {
            entityProject?.let { Text("Project to rename: ${it.name}") }
            relatedProject?.let { Text("Conflicting project: ${it.name}") }
            if (entityProject != null && relatedProject != null) {
                Button(onClick = { showRename = true }) { Text("Rename project") }
            } else {
                Text("The projects named by the server could not both be found locally.")
                Button(onClick = viewModel::retry) { Text("Retry") }
                OutlinedButton(onClick = onOpenProjects) { Text("Open projects") }
            }
        }
        if (kind == SyncIssueKind.ORPHAN_TASK) {
            taskTitle?.let { Text("Task: $it") }
            if (taskTitle != null) {
                Text("Move task to:", style = MaterialTheme.typography.titleMedium)
                projects.filterNot { it.uuid == current.relatedUuid }.forEach { project ->
                    OutlinedButton(
                        onClick = { viewModel.moveTask(checkNotNull(current.entityUuid), project.uuid) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(project.name) }
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete task") }
            } else {
                Text("The task named by the server could not be found locally.")
                Button(onClick = viewModel::retry) { Text("Retry") }
                OutlinedButton(onClick = onOpenProjects) { Text("Open projects") }
            }
        }

        if (kind !in setOf(SyncIssueKind.PROJECT_NAME_COLLISION, SyncIssueKind.ORPHAN_TASK)) {
            Button(onClick = viewModel::retry) { Text("Retry after repair") }
        }
        if (kind == SyncIssueKind.TLS_CONFIGURATION || kind == SyncIssueKind.CHECKPOINT_AHEAD ||
            kind == SyncIssueKind.REQUEST_CONFIGURATION || kind == SyncIssueKind.CLIENT_SERVER_INCOMPATIBLE ||
            kind == SyncIssueKind.PAYLOAD_TOO_LARGE || kind == SyncIssueKind.UNKNOWN_CONFLICT
        ) {
            OutlinedButton(onClick = onOpenSettings) { Text("Open setup") }
        }
        if (kind == SyncIssueKind.INVALID_SNAPSHOT || kind == SyncIssueKind.UNKNOWN_CONFLICT) {
            OutlinedButton(onClick = onOpenProjects) { Text("Open projects") }
        }

        Text("Server detail", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Text(current.detail.ifBlank { "No response detail was provided." }, Modifier.padding(12.dp).testTag("SyncIssueDetail"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    viewModel.diagnostics()?.let { diagnostics ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Sync diagnostics", diagnostics))
                    }
                }
            }) { Text("Copy diagnostics") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }

    if (showRename && entityProject != null) {
        ProjectNameDialog(
            title = "Rename project",
            initialName = entityProject!!.name,
            onDismiss = { showRename = false },
            onSave = { name ->
                viewModel.renameProject(entityProject!!.uuid, name) { showRename = false }
            },
        )
    }
    if (confirmDelete && current.entityUuid != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete task?") },
            text = { Text("This deletes the local task and syncs that deletion.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTask(current.entityUuid) { confirmDelete = false }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun issueExplanation(kind: SyncIssueKind): String = when (kind) {
    SyncIssueKind.CHECKPOINT_AHEAD ->
        "The server is behind this device's sync history. Your local tasks are safe, but sync cannot continue until the server is repaired."
    SyncIssueKind.PROJECT_NAME_COLLISION -> "Two projects would have the same name. Choose a distinct name, then sync will retry."
    SyncIssueKind.ORPHAN_TASK -> "A task points to a project the server no longer has. Move it to an existing project or delete it."
    SyncIssueKind.INVALID_SNAPSHOT -> "Local sync data failed validation. Your data was preserved; review the detail before making a repair."
    SyncIssueKind.PAYLOAD_TOO_LARGE -> "The snapshot is larger than the server accepts. Data is preserved; the app or server needs a payload-size solution."
    SyncIssueKind.REQUEST_CONFIGURATION -> "The server rejected this app's request configuration. Check setup and app/server compatibility."
    SyncIssueKind.CLIENT_SERVER_INCOMPATIBLE -> "The app received an unexpected protocol response. Check app/server versions and setup."
    SyncIssueKind.UNKNOWN_CONFLICT -> "The server reported a conflict that cannot be repaired safely automatically."
    SyncIssueKind.TLS_CONFIGURATION -> "The secure connection failed. Verify the URL, CA, client certificate and password, certificate expiry, and hostname/SAN."
}
