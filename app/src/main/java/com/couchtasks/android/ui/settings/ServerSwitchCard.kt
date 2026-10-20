package com.couchtasks.android.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couchtasks.android.viewmodel.SettingsViewModel

@Composable
fun ServerSwitchCard(viewModel: SettingsViewModel, initialUrl: String) {
    val journal by viewModel.switchJournal.collectAsStateWithLifecycle(null)
    val busy by viewModel.switchBusy.collectAsStateWithLifecycle()
    val error by viewModel.switchError.collectAsStateWithLifecycle()
    val destination by viewModel.destinationUrl.collectAsStateWithLifecycle()
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var client by rememberSaveable { mutableStateOf("") }
    var ca by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var abandon by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var pickerError by remember { mutableStateOf("") }
    val clientPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            client = uri.toString()
        }.onFailure { pickerError = "Could not retain certificate permission" }
    }
    val caPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ca = uri.toString()
        }.onFailure { pickerError = "Could not retain CA permission" }
    }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Text("Switch server", style = MaterialTheme.typography.titleLarge)
            if (journal == null) {
                OutlinedTextField(url, { url = it }, label = { Text("Destination HTTPS URL") }, enabled = !busy)
                Text("Choose credentials for the destination. Your current data stays available until switching succeeds.")
                TextButton(enabled = !busy, onClick = { clientPicker.launch(arrayOf("*/*")) }) { Text(if (client.isBlank()) "Select destination .p12" else "Destination .p12 selected") }
                TextButton(enabled = !busy, onClick = { caPicker.launch(arrayOf("*/*")) }) { Text(if (ca.isBlank()) "Select destination CA" else "Destination CA selected") }
                OutlinedTextField(password, { password = it }, label = { Text("Destination .p12 password") }, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                Text("Reset replaces local data with the destination dataset, without uploading local tasks. Migration sends task titles, notes, and status to $url after you confirm the preview. Source server data is never deleted.")
                val ready = !busy && client.isNotBlank() && ca.isNotBlank() && password.isNotBlank()
                Button(enabled = ready, onClick = { viewModel.beginSwitch(url, client, ca, password, false); password = "" }) { Text("Reset local data") }
                Button(enabled = ready, onClick = { viewModel.beginSwitch(url, client, ca, password, true); password = "" }) { Text("Preview migration") }
            } else {
                Text("Destination: $destination")
                Text("Local edits, shared imports, and synchronization are paused. Resume or abandon this switch.")
                val entry = journal!!
                if (entry.phase == "preview") {
                    Text("Projects added: ${entry.projectsAdded}; tasks copied: ${entry.tasksCopied}; tasks skipped: ${entry.tasksSkipped}.")
                    Text("Matching uses normalized project names and trimmed, case-insensitive task titles. Destination tasks win. Skipped tasks’ differing notes/status will not be merged. Another client can still create duplicates concurrently.")
                    Button(enabled = !busy, onClick = { viewModel.resumeSwitch(true) }) { Text("Confirm upload to destination") }
                } else {
                    Button(enabled = !busy, onClick = { viewModel.resumeSwitch() }) { Text("Resume") }
                }
                TextButton(enabled = !busy, onClick = { abandon = true }) { Text("Abandon") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error)
            if (pickerError.isNotBlank()) Text(pickerError)
        }
    }
    if (abandon) AlertDialog(
        onDismissRequest = { abandon = false }, title = { Text("Abandon server switch?") },
        text = { Text("Your old local dataset will remain active. An uncertain upload may already have changed the destination; abandonment does not undo those changes.") },
        confirmButton = { TextButton(onClick = { abandon = false; viewModel.abandonSwitch() }) { Text("Abandon") } },
        dismissButton = { TextButton(onClick = { abandon = false }) { Text("Cancel") } },
    )
}
