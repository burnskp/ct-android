package com.couchtasks.android.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.couchtasks.android.data.*
import com.couchtasks.android.settings.*
import com.couchtasks.android.sync.*
import com.couchtasks.android.ui.settings.SettingsScreen
import com.couchtasks.android.viewmodel.SettingsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ServerSettingsComposeTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CouchTasksDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, CouchTasksDatabase::class.java).build()
        settings = SettingsRepository(context, db)
        db.connectionDao().save(ConnectionProfile("legacy", SyncJson.encodeToString(SettingsState.serializer(), SettingsState())))
        db.connectionDao().activate(ActiveProfile(profileId = "legacy"))
        settings.setSyncStatus("", "", "Never synced")
        settings.setHealthResult("Health check not run")
        val passwords = CertificatePasswordStore(context)
        viewModel = SettingsViewModel(settings, passwords, ConfiguredSyncRunner(db, settings, passwords))
        // As in CouchTasksAppComposeTest, let the process close Room after Compose collectors stop.
    }

    private fun showSettings() {
        compose.setContent { CouchTasksTheme { SettingsScreen(viewModel) } }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun saveUrl(url: String) {
        compose.onNodeWithTag("ServerUrlField").performTextReplacement(url)
        compose.onNodeWithText("Save server URL").performClick()
    }

    @Test fun initialSetupValidatesAndCorrectsUrlWithoutLosingLocalTasks() {
        val tasks = runBlocking {
            val repository = TasksRepository(db)
            val inbox = repository.ensureInbox()
            repository.createTask(inbox.uuid, "Local task", "Keep these notes")
            db.taskDao().allTasks()
        }
        showSettings()
        compose.onNodeWithTag("ServerUrlField").assertIsDisplayed()
        compose.onNodeWithText("Switch server").assertDoesNotExist()
        compose.onNodeWithText("Destination HTTPS URL").assertDoesNotExist()
        saveUrl("http://example.com")
        waitForText("Server URL must start with https://")
        saveUrl(" https://example.com/// ")
        waitForText("Saved: https://example.com")
        saveUrl("https://corrected.example")
        waitForText("Saved: https://corrected.example")
        compose.onNodeWithText("Switch server").assertDoesNotExist()
        runBlocking {
            assertEquals(tasks, db.taskDao().allTasks())
            assertTrue(SyncExporter(db).exportRequest().tasks.any { it.title == "Local task" })
            assertEquals("https://corrected.example", SettingsRepository(context, db).currentState().serverUrl)
        }
    }

    @Test fun onlySuccessfulSyncReplacesSetupWithSwitchButton() {
        runBlocking {
            settings.setServerUrl("https://example.com")
            settings.setHealthResult("Healthy")
            settings.setSyncStatus("", "", "Network error")
        }
        showSettings()
        waitForText("Saved: https://example.com")
        compose.onNodeWithTag("ServerUrlField").assertIsDisplayed()
        compose.onNodeWithText("Switch server").assertDoesNotExist()
        runBlocking { settings.setSyncStatus("1", "2026-10-21T00:00:00Z", "Sync successful") }
        waitForText("Switch server")
        compose.onNodeWithTag("ServerUrlField").assertDoesNotExist()
        compose.onNodeWithText("https://example.com").assertIsDisplayed()
        runBlocking { settings.setSyncStatus("1", "2026-10-21T00:00:00Z", "Network error") }
        compose.onNodeWithText("Switch server").assertIsDisplayed().performClick()
        compose.onNodeWithText("Destination HTTPS URL").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Destination HTTPS URL").assertDoesNotExist()
        assertNull(runBlocking { db.connectionDao().journal() })
    }

    @Test fun restoredSuccessfulSyncShowsSwitchAndBlocksDirectUrlChanges() {
        runBlocking {
            settings.setServerUrl("https://example.com")
            settings.setSyncStatus("1", "2026-10-21T00:00:00Z", "Network error")
            assertTrue(runCatching { settings.setServerUrl("https://other.example") }.isFailure)
            assertTrue(settings.setServerUrl("https://example.com/") is ServerUrlValidationResult.Valid)
        }
        showSettings()
        waitForText("Switch server")
        compose.onNodeWithTag("ServerUrlField").assertDoesNotExist()
    }

    @Test fun pendingLegacySwitchCanBeClosedAndReopenedBeforeFirstSync() {
        runBlocking {
            settings.setServerUrl("https://example.com")
            db.connectionDao().save(ConnectionProfile("destination", SyncJson.encodeToString(SettingsState.serializer(), SettingsState(profileId = "destination", serverUrl = "https://other.example"))))
            db.connectionDao().saveJournal(SwitchJournal(profileId = "destination", mode = "migrate", phase = "preview", tasksCopied = 2))
        }
        showSettings()
        waitForText("Resume server switch")
        compose.onNodeWithTag("ServerUrlField").assertDoesNotExist()
        compose.onNodeWithText("Resume server switch").performClick()
        compose.onNodeWithText("Confirm upload to destination").assertExists()
        compose.onNodeWithText("Close").performClick()
        assertEquals("preview", runBlocking { db.connectionDao().journal()!!.phase })
        compose.onNodeWithText("Resume server switch").performClick()
        compose.onNodeWithText("Confirm upload to destination").assertExists()
        // Simulate the coordinator's successful activation while its dialog is open.
        runBlocking {
            db.connectionDao().activate(ActiveProfile(profileId = "destination"))
            db.connectionDao().clearJournal()
            settings.setSyncStatus("2", "2026-10-21T00:00:00Z", "Server switch successful")
        }
        waitForText("https://other.example")
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText("Switch server").assertIsDisplayed()
    }

    @Test fun urlSaveRechecksSuccessAfterWaitingForSync() = runBlocking {
        settings.setServerUrl("https://example.com")
        val started = CompletableDeferred<Unit>()
        val save = ConnectionOperations.mutex.withLock {
            val pending = async {
                started.complete(Unit)
                runCatching { settings.setServerUrl("https://other.example") }
            }
            started.await()
            settings.setSyncStatus("1", "2026-10-21T00:00:00Z", "Sync successful")
            pending
        }
        assertTrue(save.await().isFailure)
        assertEquals("https://example.com", settings.currentState().serverUrl)
    }
}
