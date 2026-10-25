package com.couchtasks.android

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
import com.couchtasks.android.sync.ConfiguredSyncRunner
import com.couchtasks.android.ui.CouchTasksTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    )
                }
            }
        }
    }
}

@Composable
fun CouchTasksApp(
    repository: TasksRepository,
    settingsRepository: SettingsRepository,
    certificatePasswordStore: CertificatePasswordStore,
    configuredSyncRunner: ConfiguredSyncRunner,
    switchCoordinator: com.couchtasks.android.sync.ServerSwitchCoordinator? = null,
    onLocalChange: () -> Unit = {},
) {
    CouchTasksNavHost(
        repository = repository,
        settingsRepository = settingsRepository,
        certificatePasswordStore = certificatePasswordStore,
        configuredSyncRunner = configuredSyncRunner,
        switchCoordinator = switchCoordinator,
        onLocalChange = onLocalChange,
    )
}
