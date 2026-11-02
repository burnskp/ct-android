package com.couchtasks.android

import android.app.Application
import com.couchtasks.android.data.CouchTasksDatabase
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.reminders.ReminderScheduler
import com.couchtasks.android.settings.CertificatePasswordStore
import com.couchtasks.android.settings.SettingsRepository
import com.couchtasks.android.sync.ConfiguredSyncRunner
import com.couchtasks.android.sync.ServerSwitchCoordinator
import com.couchtasks.android.sync.SyncExporter
import com.couchtasks.android.sync.SyncWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class CouchTasksApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val database = CouchTasksDatabase.create(this)
        val settingsRepository = SettingsRepository(this, database)
        val certificatePasswordStore = CertificatePasswordStore(this)
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val syncWorkScheduler = SyncWorkScheduler(this, database)
        val configuredSyncRunner = ConfiguredSyncRunner(
            database,
            settingsRepository,
            certificatePasswordStore,
            onIntervention = syncWorkScheduler::cancel,
            scheduleTransientRetry = syncWorkScheduler::enqueueTransientRetry,
        )
        val reminderScheduler = ReminderScheduler(this, database)
        val switchCoordinator = ServerSwitchCoordinator(this, database, settingsRepository, certificatePasswordStore, syncWorkScheduler::cancel)
        appContainer = AppContainer(
            switchCoordinator = switchCoordinator,
            database = database,
            tasksRepository = TasksRepository(database),
            settingsRepository = settingsRepository,
            certificatePasswordStore = certificatePasswordStore,
            configuredSyncRunner = configuredSyncRunner,
            syncExporter = SyncExporter(database),
            syncWorkScheduler = syncWorkScheduler,
            scheduleAutomaticSync = { appScope.launch { syncWorkScheduler.enqueueAutomatic() } },
            reminderScheduler = reminderScheduler,
        )
        // Any change to reminder-bearing tasks (edits, sync, deletes, server switches) re-plans the alarm. The first
        // emission is skipped: app start and boot run their own passes, and boot must keep missed reminders quiet.
        appScope.launch {
            database.reminderDao().observeCandidates().distinctUntilChanged().drop(1).collect {
                reminderScheduler.reschedule()
            }
        }
        appScope.launch { settingsRepository.initializeProfile(); switchCoordinator.cleanup(); syncWorkScheduler.enqueueAutomatic() }
    }
}

data class AppContainer(
    val switchCoordinator: ServerSwitchCoordinator,
    val database: CouchTasksDatabase,
    val tasksRepository: TasksRepository,
    val settingsRepository: SettingsRepository,
    val certificatePasswordStore: CertificatePasswordStore,
    val configuredSyncRunner: ConfiguredSyncRunner,
    val syncExporter: SyncExporter,
    val syncWorkScheduler: SyncWorkScheduler,
    val scheduleAutomaticSync: () -> Unit,
    val reminderScheduler: ReminderScheduler,
)
