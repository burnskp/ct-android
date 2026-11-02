package com.couchtasks.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.navigation.CouchTasksNavHost
import com.couchtasks.android.settings.CertificatePasswordStore
import com.couchtasks.android.settings.SettingsRepository
import com.couchtasks.android.sync.ConfiguredSyncRunner
import com.couchtasks.android.ui.CouchTasksTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var openTaskRequest by mutableStateOf<OpenTaskRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = (application as CouchTasksApplication).appContainer
        // Re-arms the reminder alarm after a force stop, which clears it, and posts anything due now.
        lifecycleScope.launch { appContainer.reminderScheduler.reschedule() }
        if (savedInstanceState == null) openTaskRequest = intent.openTaskRequest()
        setContent {
            CouchTasksTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CouchTasksApp(
                        appContainer.tasksRepository,
                        appContainer.settingsRepository,
                        appContainer.certificatePasswordStore,
                        appContainer.configuredSyncRunner,
                        switchCoordinator = appContainer.switchCoordinator,
                        onLocalChange = appContainer.scheduleAutomaticSync,
                        openTaskRequest = openTaskRequest,
                        onOpenTaskHandled = { openTaskRequest = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.openTaskRequest()?.let { openTaskRequest = it }
    }

    private fun Intent.openTaskRequest(): OpenTaskRequest? {
        val projectUuid = getStringExtra(EXTRA_PROJECT_UUID) ?: return null
        val taskUuid = getStringExtra(EXTRA_TASK_UUID) ?: return null
        return OpenTaskRequest(projectUuid, taskUuid)
    }

    companion object {
        const val EXTRA_PROJECT_UUID = "com.couchtasks.android.extra.PROJECT_UUID"
        const val EXTRA_TASK_UUID = "com.couchtasks.android.extra.TASK_UUID"
    }
}

/** A task to open on arrival, such as from a reminder notification. */
data class OpenTaskRequest(val projectUuid: String, val taskUuid: String)

@Composable
fun CouchTasksApp(
    repository: TasksRepository,
    settingsRepository: SettingsRepository,
    certificatePasswordStore: CertificatePasswordStore,
    configuredSyncRunner: ConfiguredSyncRunner,
    switchCoordinator: com.couchtasks.android.sync.ServerSwitchCoordinator? = null,
    onLocalChange: () -> Unit = {},
    openTaskRequest: OpenTaskRequest? = null,
    onOpenTaskHandled: () -> Unit = {},
) {
    CouchTasksNavHost(
        repository = repository,
        settingsRepository = settingsRepository,
        certificatePasswordStore = certificatePasswordStore,
        configuredSyncRunner = configuredSyncRunner,
        switchCoordinator = switchCoordinator,
        onLocalChange = onLocalChange,
        openTaskRequest = openTaskRequest,
        onOpenTaskHandled = onOpenTaskHandled,
    )
}
