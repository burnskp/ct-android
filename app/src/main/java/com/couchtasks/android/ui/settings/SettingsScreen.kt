package com.couchtasks.android.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couchtasks.android.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsViewModel: SettingsViewModel) {
    val settings = settingsViewModel.settings.collectAsStateWithLifecycle(initialValue = null).value ?: return
    val tlsErrorDialogMessage by settingsViewModel.tlsErrorDialogMessage.collectAsStateWithLifecycle(null)
    val switchJournal by settingsViewModel.switchJournal.collectAsStateWithLifecycle()
    var serverUrl by rememberSaveable(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var serverUrlError by rememberSaveable { mutableStateOf("") }
    var switchDialogProfile by rememberSaveable { mutableStateOf<String?>(null) }
    val reviewIntervalDays by settingsViewModel.reviewIntervalDays.collectAsStateWithLifecycle(null)
    var reviewIntervalText by rememberSaveable(reviewIntervalDays) { mutableStateOf(reviewIntervalDays?.toString().orEmpty()) }

    LaunchedEffect(settings.profileId) {
        if (switchDialogProfile != null && switchDialogProfile != settings.profileId) {
            switchDialogProfile = null
        }
    }
    if (switchDialogProfile != null) {
        ServerSwitchDialog(settingsViewModel, settings.serverUrl, onDismiss = { switchDialogProfile = null })
    }

    var pendingClientCertificateUri by remember { mutableStateOf<Uri?>(null) }
    val clientCertificateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingClientCertificateUri = uri
        }
    }
    val caCertificateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            settingsViewModel.persistCaCertificate(uri)
        }
    }

    tlsErrorDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { settingsViewModel.dismissTlsErrorDialog() },
            title = { Text("TLS/certificate error") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { settingsViewModel.dismissTlsErrorDialog() }) {
                    Text("OK")
                }
            },
        )
    }

    pendingClientCertificateUri?.let { uri ->
        ClientCertificatePasswordDialog(
            onDismiss = { pendingClientCertificateUri = null },
            onSave = { password, onSaved, onInvalid ->
                settingsViewModel.persistClientCertificate(
                    uri = uri,
                    password = password,
                    onSaved = {
                        pendingClientCertificateUri = null
                        onSaved()
                    },
                    onInvalid = onInvalid,
                )
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Sync") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("SettingsList"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SyncSummaryCard(settings) }
            if (settings.lastSyncTimestamp.isBlank() && switchJournal == null) {
                item {
                    ServerUrlCard(
                        value = serverUrl,
                        savedValue = settings.serverUrl,
                        error = serverUrlError,
                        onValueChange = { serverUrl = it; serverUrlError = "" },
                        onSave = {
                            settingsViewModel.saveServerUrl(
                                serverUrl,
                                onValid = { serverUrl = it; serverUrlError = "" },
                                onInvalid = { serverUrlError = it },
                            )
                        },
                    )
                }
            } else {
                item { SettingsCard("Server URL", settings.serverUrl.ifBlank { "Not configured" }) }
                item {
                    Button(
                        onClick = { switchDialogProfile = settings.profileId },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (switchJournal != null) "Resume server switch" else "Switch server")
                    }
                }
            }
            item {
                CertificateCard(
                    title = "Client certificate",
                    emptyText = "No .p12 selected",
                    displayName = settings.clientCertificateDisplayName,
                    sizeBytes = settings.clientCertificateSizeBytes,
                    sha256Fingerprint = settings.clientCertificateSha256Fingerprint,
                    onSelect = { clientCertificateLauncher.launch(arrayOf("*/*")) },
                )
            }
            item {
                CertificateCard(
                    title = "CA certificate",
                    emptyText = "No CA certificate selected",
                    displayName = settings.caCertificateDisplayName,
                    sizeBytes = settings.caCertificateSizeBytes,
                    sha256Fingerprint = settings.caCertificateSha256Fingerprint,
                    onSelect = { caCertificateLauncher.launch(arrayOf("*/*")) },
                )
            }
            item { SettingsCard("Checkpoint", settings.checkpoint.ifBlank { "Not synced yet" }) }
            item { SettingsCard("Last health", settings.lastHealthResult, successValue = "Healthy") }
            item { SettingsCard("Last sync", settings.lastSyncResult, successValue = "Sync successful") }
            reviewIntervalDays?.let { savedDays ->
                item {
                    ReviewIntervalCard(
                        value = reviewIntervalText,
                        savedDays = savedDays,
                        onValueChange = { reviewIntervalText = it },
                        onSave = { settingsViewModel.saveReviewIntervalDays(reviewIntervalText) },
                    )
                }
            }
            item {
                Button(
                    onClick = { settingsViewModel.healthCheck() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Health check") }
            }
            item {
                Button(
                    onClick = { settingsViewModel.syncNow() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sync now") }
            }
            item {
                Text(
                    "Health checks and sync report concise local status; green is reserved for successful health or sync results.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ClientCertificatePasswordDialog(
    onDismiss: () -> Unit,
    onSave: (
        password: String,
        onSaved: () -> Unit,
        onInvalid: (String) -> Unit,
    ) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Client .p12 password") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    error = ""
                },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = error.isNotBlank(),
                supportingText = { if (error.isNotBlank()) Text(error) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        password,
                        { password = "" },
                        { message -> error = message },
                    )
                },
                enabled = password.isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
