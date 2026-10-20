package com.couchtasks.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.navigation.CouchTasksNavHost
import com.couchtasks.android.settings.CertificatePasswordStore
import com.couchtasks.android.settings.SettingsRepository
import com.couchtasks.android.share.ShareReceiverActivity
import com.couchtasks.android.sync.ConfiguredSyncRunner
import com.couchtasks.android.ui.CouchTasksTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    private val openTaskRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openTaskRequest.value = intent.openTaskUuidExtra()
        val appContainer = (application as CouchTasksApplication).appContainer
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
                        onOpenTaskRequestHandled = { openTaskRequest.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTaskRequest.value = intent.openTaskUuidExtra()
    }
}

private fun Intent.openTaskUuidExtra(): String? =
    getStringExtra(ShareReceiverActivity.EXTRA_OPEN_TASK_UUID)?.takeIf { it.isNotBlank() }

@Composable
fun CouchTasksApp(
    repository: TasksRepository,
    settingsRepository: SettingsRepository,
    certificatePasswordStore: CertificatePasswordStore,
    configuredSyncRunner: ConfiguredSyncRunner,
    switchCoordinator: com.couchtasks.android.sync.ServerSwitchCoordinator? = null,
    onLocalChange: () -> Unit = {},
    openTaskRequest: StateFlow<String?>? = null,
    onOpenTaskRequestHandled: () -> Unit = {},
) {
    CouchTasksNavHost(
        repository = repository,
        settingsRepository = settingsRepository,
        certificatePasswordStore = certificatePasswordStore,
        configuredSyncRunner = configuredSyncRunner,
        switchCoordinator = switchCoordinator,
        onLocalChange = onLocalChange,
        openTaskRequest = openTaskRequest,
        onOpenTaskRequestHandled = onOpenTaskRequestHandled,
    )
}
