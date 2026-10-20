package com.couchtasks.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.couchtasks.android.data.*
import com.couchtasks.android.settings.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ServerSwitchInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CouchTasksDatabase
    private lateinit var settings: SettingsRepository
    private val passwords = CertificatePasswordStore(context)
    private val name = "switch-test-${System.nanoTime()}.db"
    private val time = "2026-01-01T00:00:00Z"
    private val remote = SyncProject("destination-project", "Work", "work", time, time, syncRevision = 10, syncDirty = false)
    private var outgoing = mutableListOf<SyncRequest>()
    private var failUpload = false
    private var failActivation = false
    private var failFetch = false

    @Before fun setup() = runBlocking {
        open()
        settings.initializeProfile()
        db.connectionDao().save(ConnectionProfile("legacy", SyncJson.encodeToString(SettingsState.serializer(), SettingsState(serverUrl = "https://old.example"))))
        val repo = TasksRepository(db)
        val project = repo.createProject("Work")
        repo.createTask(project.uuid, "Read", "private notes")
        db.syncStateDao().set(SyncStateEntity(SyncExporter.CHECKPOINT_KEY, "999"))
        db.tombstoneDao().upsert(SyncTombstoneEntity("task", "old-deletion", 12, time))
    }
    private fun open() {
        db = Room.databaseBuilder(context, CouchTasksDatabase::class.java, name).build()
        settings = SettingsRepository(context, db)
    }
    @After fun teardown() = runBlocking {
        coordinator().abandon()
        db.close()
        context.deleteDatabase(name)
    }
    private fun coordinator() = ServerSwitchCoordinator(context, db, settings, passwords, {}, exchange = { _, request ->
        if (request.checkpoint == 0L) {
            if (failFetch) error("fetch failed")
            SyncTransportResult.Success(SyncResponse(10, projects = listOf(remote)))
        } else {
            outgoing += request
            if (failUpload) error("server committed, response lost")
            SyncTransportResult.Success(SyncResponse(11, projects = request.projects.map { it.copy(syncDirty = false, syncRevision = 10) },
                tasks = request.tasks.map { it.copy(syncDirty = false, syncRevision = 11) }))
        }
    }, beforeActivation = { if (failActivation) error("activation failed") })
    private suspend fun begin(migrate: Boolean = true) = coordinator().begin("https://new.example", "content://client", "content://ca", "test-password", migrate)

    @Test fun unchangedCanonicalUrlIsNoOp() = runBlocking {
        val original = SyncExporter(db).exportRequest()
        coordinator().begin("HTTPS://OLD.EXAMPLE:443///", "", "", "", true)
        assertEquals(original, SyncExporter(db).exportRequest())
        assertNull(db.connectionDao().journal())
    }

    @Test fun resetFetchesWithoutUploadingAndDropsSourceCheckpointAndTombstones() = runBlocking {
        begin(false)
        assertTrue(outgoing.isEmpty())
        assertTrue(db.taskDao().allTasks().isEmpty())
        assertTrue(db.tombstoneDao().allTombstones().isEmpty())
        assertEquals("10", db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
        assertEquals("https://new.example", settings.currentState().serverUrl)
        assertNull(db.connectionDao().journal())
    }

    @Test fun previewAndLostResponseKeepOldDataAndRetryExactRequestAfterReopening() = runBlocking {
        val original = SyncExporter(db).exportRequest()
        begin()
        assertTrue(outgoing.isEmpty())
        assertEquals("preview", db.connectionDao().journal()!!.phase)
        assertEquals(original, SyncExporter(db).exportRequest())
        assertTrue(runCatching { TasksRepository(db).createProject("Blocked") }.isFailure)
        assertEquals(ConfiguredSyncResult.SwitchInProgress, ConfiguredSyncRunner(db, settings, passwords).syncNow())
        assertEquals(ConfiguredHealthResult.SwitchInProgress, ConfiguredSyncRunner(db, settings, passwords).healthCheck())
        assertTrue(runCatching { settings.setCertificateUris("content://other", "content://other-ca") }.isFailure)
        assertTrue(runCatching { TasksRepository(db).importSharedTask("draft", "Read", "notes") }.isFailure)
        failUpload = true
        assertTrue(runCatching { coordinator().resume(true) }.isFailure)
        val sent = outgoing.single()
        assertEquals(10L, sent.checkpoint)
        assertTrue(sent.tombstones.isEmpty())
        assertEquals(original, SyncExporter(db).exportRequest())
        assertEquals("https://old.example", settings.currentState().serverUrl)
        db.close()
        open()
        failUpload = false
        coordinator().resume()
        assertEquals(sent, outgoing.last())
        assertEquals(1, db.taskDao().allTasks().size)
        assertNotEquals(original.tasks.single().uuid, db.taskDao().allTasks().single().uuid)
        assertEquals("private notes", db.taskDao().allTasks().single().notes)
        assertEquals("https://new.example", settings.currentState().serverUrl)
    }

    @Test fun largeMigrationRequestSurvivesReopeningWithoutCursorWindowOverflow() = runBlocking {
        val repository = TasksRepository(db)
        val project = db.projectDao().allProjects().single()
        repeat(300) { repository.createTask(project.uuid, "Large task $it", "😀".repeat(2000)) }
        begin()
        val digest = db.connectionDao().journal()!!.request
        assertEquals(64, digest.length)
        db.close()
        open()
        coordinator().resume(true)
        assertEquals(301, db.taskDao().allTasks().size)
        assertEquals(301, outgoing.single().tasks.count { it.syncDirty })
    }

    @Test fun activationFailureIsRecoverableWithoutUploadingAgain() = runBlocking {
        val original = SyncExporter(db).exportRequest()
        begin()
        failActivation = true
        assertTrue(runCatching { coordinator().resume(true) }.isFailure)
        assertEquals("activate", db.connectionDao().journal()!!.phase)
        assertEquals(original, SyncExporter(db).exportRequest())
        db.close()
        open()
        failActivation = false
        coordinator().resume()
        assertEquals(1, outgoing.size)
        assertEquals("https://new.example", settings.currentState().serverUrl)
    }

    @Test fun fetchFailureAndAbandonPreserveSource() = runBlocking {
        val original = SyncExporter(db).exportRequest()
        failFetch = true
        assertTrue(runCatching { begin() }.isFailure)
        assertEquals("fetch", db.connectionDao().journal()!!.phase)
        coordinator().abandon()
        assertEquals(original, SyncExporter(db).exportRequest())
        assertEquals("https://old.example", settings.currentState().serverUrl)
        assertNull(db.connectionDao().journal())
    }

    @Test fun uncertainUploadCanBeAbandonedWithoutChangingSource() = runBlocking {
        val original = SyncExporter(db).exportRequest()
        begin()
        failUpload = true
        runCatching { coordinator().resume(true) }
        coordinator().abandon()
        assertEquals(original, SyncExporter(db).exportRequest())
        assertEquals("https://old.example", settings.currentState().serverUrl)
    }
}
