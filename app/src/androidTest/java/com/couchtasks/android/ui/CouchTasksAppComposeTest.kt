package com.couchtasks.android.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.couchtasks.android.CouchTasksApp
import com.couchtasks.android.data.CouchTasksDatabase
import com.couchtasks.android.data.SyncIssueEntity
import com.couchtasks.android.data.TaskStatus
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.settings.CertificatePasswordStore
import com.couchtasks.android.settings.SettingsRepository
import com.couchtasks.android.sync.ConfiguredSyncRunner
import com.couchtasks.android.sync.SyncIssueKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CouchTasksAppComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: CouchTasksDatabase
    private lateinit var repository: TasksRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var certificatePasswordStore: CertificatePasswordStore
    private lateinit var configuredSyncRunner: ConfiguredSyncRunner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CouchTasksDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TasksRepository(db)
        settingsRepository = SettingsRepository(context, db)
        runBlocking {
            db.connectionDao().save(com.couchtasks.android.data.ConnectionProfile(
                "legacy",
                com.couchtasks.android.sync.SyncJson.encodeToString(
                    com.couchtasks.android.settings.SettingsState.serializer(),
                    com.couchtasks.android.settings.SettingsState(),
                ),
            ))
            db.connectionDao().activate(com.couchtasks.android.data.ActiveProfile(profileId = "legacy"))
            settingsRepository.setSyncStatus("", "", "Never synced")
            settingsRepository.setHealthResult("Health check not run")
        }
        certificatePasswordStore = CertificatePasswordStore(context)
        configuredSyncRunner = ConfiguredSyncRunner(db, settingsRepository, certificatePasswordStore)
    }

    @After
    fun tearDown() {
        // The compose content can still be collecting Room flows briefly after a test
        // finishes; closing the in-memory database here races those collectors on
        // instrumentation devices. Let the process clean up these short-lived DBs.
    }

    @Test
    fun launch_showsAllTab() {
        setAppContent()

        compose.onNodeWithText("All").assertIsDisplayed()
        compose.onAllNodesWithText("All Tasks").assertCountEquals(0)
        compose.onAllNodesWithText("Tasks by Project").assertCountEquals(0)
    }

    @Test
    fun launch_createsAndShowsInboxOnProjectsTab() {
        setAppContent()

        compose.onNodeWithText("Projects").performClick()
        compose.waitUntilNodeWithText("Inbox")
        compose.onNodeWithText("Inbox").assertIsDisplayed()
    }

    @Test
    fun allTasks_hidesEmptyProjects() {
        runBlocking { repository.ensureInbox() }

        setAppContent()

        compose.onAllNodesWithText("Inbox").assertCountEquals(0)
    }

    @Test
    fun projectCounts_areVisibleOnProjectsTab() {
        runBlocking {
            val inbox = repository.ensureInbox()
            repository.createTask(inbox.uuid, "Pending task")
            repository.completeTask(repository.createTask(inbox.uuid, "Done task").uuid)
        }

        setAppContent()

        compose.onNodeWithText("Projects").performClick()
        compose.waitUntilNodeWithText("Inbox")
        compose.onNodeWithText("1 pending · 1 done").assertIsDisplayed()
    }

    @Test
    fun projectActions_areShownOnProjectDetail() {
        runBlocking { repository.ensureInbox() }

        setAppContent()

        compose.onNodeWithText("Projects").performClick()
        compose.waitUntilNodeWithText("Inbox")
        compose.onAllNodesWithText("Your Projects").assertCountEquals(0)
        compose.onAllNodesWithText("Edit").assertCountEquals(0)
        compose.onAllNodesWithText("Delete").assertCountEquals(0)

        compose.onNodeWithText("Inbox").performClick()
        compose.onNodeWithContentDescription("Project actions for Inbox").performClick()

        compose.waitUntilNodeWithText("Rename")
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun projectMenuRename_opensRenameDialog() {
        runBlocking { repository.ensureInbox() }

        setAppContent()

        openInboxProject()
        compose.onNodeWithContentDescription("Project actions for Inbox").performClick()
        compose.onNodeWithText("Rename").performClick()

        compose.waitUntilNodeWithText("Rename Project")
        compose.onNodeWithText("Rename Project").assertIsDisplayed()
        compose.onNodeWithTag("ProjectNameField").assertIsDisplayed()
    }

    @Test
    fun projectMenuDelete_requiresConfirmation() {
        lateinit var inboxUuid: String
        runBlocking { inboxUuid = repository.ensureInbox().uuid }

        setAppContent()

        openInboxProject()
        compose.onNodeWithContentDescription("Project actions for Inbox").performClick()
        compose.onNodeWithText("Delete").performClick()

        compose.waitUntilNodeWithText("Delete Project")
        compose.onNodeWithText("Delete Project").assertIsDisplayed()
        compose.onNodeWithText("Inbox").assertIsDisplayed()
        assertNotNull(runBlocking { db.projectDao().get(inboxUuid) })

        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking { db.projectDao().get(inboxUuid) == null }
        }
    }

    @Test
    fun allTasks_hidesDoneTasks() {
        runBlocking {
            val inbox = repository.ensureInbox()
            repository.createTask(inbox.uuid, "Pending task")
            repository.completeTask(repository.createTask(inbox.uuid, "Done task").uuid)
        }

        setAppContent()

        compose.waitUntilNodeWithText("Pending task")
        compose.onNodeWithText("Pending task").assertIsDisplayed()
        compose.onAllNodesWithText("Done task").assertCountEquals(0)
    }

    @Test
    fun allTasksProjectHeaders_areVisualOnly() {
        runBlocking {
            val inbox = repository.ensureInbox()
            repository.createTask(inbox.uuid, "Visible task")
        }

        setAppContent()

        compose.waitUntilNodeWithText("Inbox")
        compose.onNodeWithText("Inbox").assertHasNoClickAction()
    }

    @Test
    fun firstPagePlus_createsTaskDefaultingToInbox() {
        setAppContent()

        compose.waitUntilInboxCreated()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("Project: Inbox").assertIsDisplayed()
        compose.onNodeWithTag("AddTaskTitleField").performTextInput("Quick task")
        compose.onNodeWithTag("AddTaskSaveButton").performClick()

        compose.waitUntilNodeWithText("Quick task")
        compose.onNodeWithText("Quick task").assertIsDisplayed()
    }

    @Test
    fun taskAddMenu_canCreateNewProjectAndAddTaskToIt() {
        setAppContent()

        compose.waitUntilInboxCreated()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("Project: Inbox").performClick()
        compose.onNodeWithText("New project…").performClick()
        compose.onNodeWithTag("ProjectNameField").performTextInput("Errands")
        compose.onNodeWithTag("ProjectNameSaveButton").performClick()
        compose.waitUntilNodeWithText("Project: Errands")
        compose.onNodeWithTag("AddTaskTitleField").performTextInput("Pick up package")
        compose.onNodeWithTag("AddTaskSaveButton").performClick()

        compose.waitUntilNodeWithText("Errands")
        compose.onNodeWithText("Errands").assertIsDisplayed()
        compose.onNodeWithText("Pick up package").assertIsDisplayed()
    }

    @Test
    fun projectsTabProject_opensPendingAndDoneTabs() {
        setAppContent()

        openInboxProject()
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun projectsBottomTabFromProjectDetail_returnsToProjectsList() {
        setAppContent()

        openInboxProject()
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.onNodeWithText("Projects").performClick()

        compose.waitUntilNodeWithText("0 pending · 0 done")
        compose.onNodeWithText("0 pending · 0 done").assertIsDisplayed()
        compose.onAllNodesWithText("Pending").assertCountEquals(0)
    }

    @Test
    fun pendingAndDoneTabs_filterTaskRows() {
        runBlocking {
            val inbox = repository.ensureInbox()
            repository.createTask(inbox.uuid, "Pending only")
            repository.completeTask(repository.createTask(inbox.uuid, "Done only").uuid)
        }

        setAppContent()

        openInboxProject()
        compose.waitUntilNodeWithText("Pending only")
        compose.onNodeWithText("Pending only").assertIsDisplayed()
        compose.onAllNodesWithText("Done only").assertCountEquals(0)

        compose.onNodeWithText("Done").performClick()
        compose.waitUntilNodeWithText("Done only")
        compose.onNodeWithText("Done only").assertIsDisplayed()
        compose.onAllNodesWithText("Pending only").assertCountEquals(0)
    }

    @Test
    fun clearDone_removesDoneTasks() {
        runBlocking {
            val inbox = repository.ensureInbox()
            repository.completeTask(repository.createTask(inbox.uuid, "Finished task").uuid)
        }

        setAppContent()

        openInboxProject()
        compose.onNodeWithText("Done").performClick()
        compose.waitUntilNodeWithText("Finished task")
        compose.onNodeWithText("Clear Done").performClick()
        compose.onNodeWithText("Clear").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Finished task").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("No done tasks").assertIsDisplayed()
    }

    @Test
    fun taskEditor_showsGoalFieldsAndNoNonGoalFields() {
        setAppContent()

        openInboxProject()
        compose.onNodeWithText("+").performClick()

        compose.onNodeWithText("New Task").assertIsDisplayed()
        compose.onNodeWithText("Title").assertIsDisplayed()
        compose.onNodeWithText("Notes").assertIsDisplayed()
        compose.onNodeWithText("Project: Inbox").assertIsDisplayed()
        compose.onNodeWithText("Status").assertIsDisplayed()
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onAllNodesWithText("Due date").assertCountEquals(0)
        compose.onAllNodesWithText("Reminder").assertCountEquals(0)
        compose.onAllNodesWithText("Priority").assertCountEquals(0)
        compose.onAllNodesWithText("Tags").assertCountEquals(0)
    }

    @Test
    fun pressingImeDoneInNewTaskTitle_savesTask() {
        setAppContent()

        openInboxProject()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithTag("TaskTitleField").performTextInput("Keyboard task")
        compose.onNodeWithTag("TaskTitleField").performImeAction()

        compose.waitUntilNodeWithText("Keyboard task")
        compose.onNodeWithText("Keyboard task").assertIsDisplayed()
    }

    @Test
    fun reorderPendingTasks_movesRowsAndKeepsCompactOrder() {
        lateinit var projectUuid: String
        lateinit var firstUuid: String
        lateinit var secondUuid: String
        lateinit var thirdUuid: String
        runBlocking {
            val inbox = repository.ensureInbox()
            projectUuid = inbox.uuid
            firstUuid = repository.createTask(inbox.uuid, "First task").uuid
            secondUuid = repository.createTask(inbox.uuid, "Second task").uuid
            thirdUuid = repository.createTask(inbox.uuid, "Third task").uuid
        }

        setAppContent()

        openInboxProject()
        compose.waitUntilNodeWithText("First task")
        compose.onAllNodesWithText("≡").assertCountEquals(0)
        compose.onAllNodesWithText("↑").assertCountEquals(0)
        compose.onAllNodesWithText("↓").assertCountEquals(0)
        compose.onAllNodesWithText("⋮").assertCountEquals(0)
        compose.onNodeWithText("First task").performTouchInput {
            down(center)
            advanceEventTime(1_000)
            moveBy(Offset(0f, 140f))
            up()
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                db.taskDao().pendingTasks(projectUuid).map { it.uuid to it.sortOrder } == listOf(
                    secondUuid to 1,
                    firstUuid to 2,
                    thirdUuid to 3,
                )
            }
        }
    }

    @Test
    fun taskRowCheckbox_stillCompletesAndReopensTask() {
        lateinit var taskUuid: String
        runBlocking {
            val inbox = repository.ensureInbox()
            taskUuid = repository.createTask(inbox.uuid, "Checkbox task").uuid
        }

        setAppContent()

        openInboxProject()
        compose.waitUntilNodeWithText("Checkbox task")
        compose.onNodeWithTag("TaskCheckbox-$taskUuid").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking { db.taskDao().get(taskUuid)?.status == TaskStatus.Done }
        }
        compose.onNodeWithText("Done").performClick()
        compose.waitUntilNodeWithText("Checkbox task")
        compose.onNodeWithTag("TaskCheckbox-$taskUuid").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking { db.taskDao().get(taskUuid)?.status == TaskStatus.Pending }
        }
    }

    @Test
    fun openTaskRequest_navigatesToExistingTaskEditorAndClearsRequest() {
        lateinit var taskUuid: String
        val openTaskRequest = MutableStateFlow<String?>(null)
        runBlocking {
            val inbox = repository.ensureInbox()
            taskUuid = repository.createTask(inbox.uuid, "Shared task", "https://example.com").uuid
        }

        compose.setContent {
            CouchTasksTheme {
                CouchTasksApp(
                    repository = repository,
                    settingsRepository = settingsRepository,
                    certificatePasswordStore = certificatePasswordStore,
                    configuredSyncRunner = configuredSyncRunner,
                    openTaskRequest = openTaskRequest,
                    onOpenTaskRequestHandled = { openTaskRequest.value = null },
                )
            }
        }
        openTaskRequest.value = taskUuid

        compose.waitUntilNodeWithText("Edit Task")
        compose.onNodeWithText("Edit Task").assertIsDisplayed()
        compose.onNodeWithText("Shared task").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 5_000) { openTaskRequest.value == null }
    }

    @Test
    fun settingsScreen_showsRequiredSyncFields() {
        setAppContent()

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Settings & Sync").assertIsDisplayed()
        compose.onNodeWithText("Sync Summary").assertIsDisplayed()
        compose.onAllNodesWithText("Server URL").assertCountEquals(2)
        compose.onNodeWithTag("SettingsList").performScrollToNode(hasText("Client certificate"))
        compose.onNodeWithText("Client certificate").assertIsDisplayed()
        compose.onNodeWithTag("SettingsList").performScrollToNode(hasText("CA certificate"))
        compose.onNodeWithText("CA certificate").assertIsDisplayed()
        compose.onNodeWithTag("SettingsList").performScrollToNode(hasText("Checkpoint"))
        compose.onNodeWithText("Checkpoint").assertIsDisplayed()
        compose.onNodeWithTag("SettingsList").performScrollToNode(hasText("Sync now"))
        compose.onNodeWithText("Sync now").assertIsDisplayed()
    }

    @Test
    fun syncIssueBannerOpensResolutionAndCancelDoesNotClearIssue() {
        runBlocking {
            db.syncIssueDao().upsert(
                SyncIssueEntity(
                    kind = SyncIssueKind.UNKNOWN_CONFLICT.name,
                    httpStatus = 409,
                    detail = "Server conflict detail",
                    requestCheckpoint = 3,
                    detectedAt = "2026-09-04T10:00:00Z",
                ),
            )
        }
        setAppContent()

        compose.onNodeWithTag("SyncIssueBanner").assertIsDisplayed().performClick()
        compose.onNodeWithTag("SyncIssueScreen").assertIsDisplayed()
        compose.onNodeWithText("Server conflict detail").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("SyncIssueBanner").assertIsDisplayed()
        assertNotNull(runBlocking { db.syncIssueDao().get() })
    }

    @Test
    fun projectsScreen_doesNotShowSyncSummary() {
        setAppContent()

        compose.onNodeWithText("Projects").performClick()
        compose.onAllNodesWithText("Sync Summary").assertCountEquals(0)
    }

    @Test
    fun settingsScreen_validatesAndSavesServerUrl() {
        setAppContent()

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("ServerUrlField").performTextClearance()
        compose.onNodeWithTag("ServerUrlField").performTextInput("http://example.com")
        compose.onNodeWithText("Save server URL").performClick()
        compose.waitUntilNodeWithText("Server URL must start with https://")

        compose.onNodeWithTag("ServerUrlField").performTextClearance()
        compose.onNodeWithTag("ServerUrlField").performTextInput(" https://example.com/// ")
        compose.onNodeWithText("Save server URL").performClick()
        compose.waitUntilNodeWithText("Saved: https://example.com")
    }

    private fun openInboxProject() {
        compose.onNodeWithText("Projects").performClick()
        compose.waitUntilNodeWithText("Inbox")
        compose.onNodeWithText("Inbox").performClick()
    }

    private fun setAppContent() {
        compose.setContent {
            CouchTasksTheme {
                CouchTasksApp(repository, settingsRepository, certificatePasswordStore, configuredSyncRunner)
            }
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilNodeWithText(text: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilInboxCreated() {
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { db.projectDao().getByNormalizedName("inbox") != null }
        }
    }
}
