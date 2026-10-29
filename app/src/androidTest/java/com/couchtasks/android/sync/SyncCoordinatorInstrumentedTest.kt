package com.couchtasks.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.couchtasks.android.data.CouchTasksDatabase
import com.couchtasks.android.data.SyncStateEntity
import com.couchtasks.android.data.SyncIssueEntity
import com.couchtasks.android.data.TaskStatus
import com.couchtasks.android.data.TasksRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SyncCoordinatorInstrumentedTest {
    private lateinit var db: CouchTasksDatabase
    private lateinit var repository: TasksRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CouchTasksDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TasksRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun successfulSync_clearsDirtyFlagsAndAdvancesCheckpoint() = runBlocking {
        val project = repository.createProject("Home")
        val task = repository.createTask(project.uuid, "Read", "https://example.com")
        var requestBody = ""
        val coordinator = coordinatorReturning { request ->
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            requestBody = buffer.readUtf8()
            response(
                body = """
                {
                  "checkpoint": 10,
                  "projects": [${projectJson(project.uuid, "Home", "home", 1, syncDirty = false)}],
                  "tasks": [${taskJson(task.uuid, project.uuid, "Read", "https://example.com", 1, 2, syncDirty = false)}],
                  "tombstones": []
                }
                """.trimIndent(),
            )
        }

        val result = coordinator.syncOnce("https://tasks.example.com")

        assertTrue(result is SyncRunResult.Success)
        assertTrue(requestBody.contains("\"checkpoint\":0"))
        assertTrue(requestBody.contains("\"uuid\":\"${project.uuid}\""))
        assertTrue(requestBody.contains("\"sync_dirty\":true"))
        assertTrue(requestBody.contains("\"reviewed_at\":\"${task.createdAt}\""))
        assertEquals("10", db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
        assertEquals(1, db.projectDao().get(project.uuid)!!.syncRevision)
        assertFalse(db.projectDao().get(project.uuid)!!.syncDirty)
        assertEquals(2, db.taskDao().get(task.uuid)!!.syncRevision)
        assertFalse(db.taskDao().get(task.uuid)!!.syncDirty)
    }

    @Test
    fun failedHttpSync_preservesCheckpointAndDirtyDatabaseState() = runBlocking {
        db.syncStateDao().set(SyncStateEntity(SyncExporter.CHECKPOINT_KEY, "7"))
        val project = repository.createProject("Home")
        val coordinator = coordinatorReturning { response(code = 503, body = "down") }

        val result = coordinator.syncOnce("https://tasks.example.com")

        assertTrue(result is SyncRunResult.RetryableFailure)
        assertEquals("7", db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
        assertTrue(db.projectDao().get(project.uuid)!!.syncDirty)
        assertEquals(0, db.projectDao().get(project.uuid)!!.syncRevision)
    }

    @Test
    fun conflictPersistsIssueWithoutChangingAnySyncState() = runBlocking {
        db.syncStateDao().set(SyncStateEntity(SyncExporter.CHECKPOINT_KEY, "7"))
        val project = repository.createProject("Home")
        val detail = "client checkpoint 7 is ahead of server checkpoint 5; sync another current client or rebuild the server"
        val coordinator = coordinatorReturning { response(code = 409, body = detail) }

        val result = coordinator.syncOnce("https://tasks.example.com")

        assertTrue(result is SyncRunResult.NeedsIntervention)
        assertEquals("7", db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
        assertTrue(db.projectDao().get(project.uuid)!!.syncDirty)
        assertEquals(0, db.projectDao().get(project.uuid)!!.syncRevision)
        val issue = db.syncIssueDao().get()!!
        assertEquals(SyncIssueKind.CHECKPOINT_AHEAD.name, issue.kind)
        assertEquals(409, issue.httpStatus)
        assertEquals(7, issue.requestCheckpoint)
        assertEquals(5L, issue.serverCheckpoint)
        assertEquals(detail, issue.detail)
    }

    @Test
    fun successfulResponseClearsIssueInSameTransaction() = runBlocking {
        db.syncIssueDao().upsert(
            SyncIssueEntity(
                kind = SyncIssueKind.UNKNOWN_CONFLICT.name,
                httpStatus = 409,
                detail = "old conflict",
                requestCheckpoint = 0,
                detectedAt = "2026-09-04T10:00:00Z",
            ),
        )
        val coordinator = coordinatorReturning { response(body = emptySyncResponse(checkpoint = 1)) }

        assertTrue(coordinator.syncOnce("https://tasks.example.com") is SyncRunResult.Success)
        assertEquals("1", db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
        assertNull(db.syncIssueDao().get())
    }

    @Test
    fun preflightRepairsNamesAndPendingOrderBeforeSending() = runBlocking {
        val project = repository.createProject("Home")
        val first = repository.createTask(project.uuid, "First")
        val second = repository.createTask(project.uuid, "Second")
        db.projectDao().upsert(project.copy(name = "  HOME  ", nameNormalized = "stale", syncDirty = false))
        db.taskDao().upsert(first.copy(sortOrder = 8, syncDirty = false))
        db.taskDao().upsert(second.copy(sortOrder = 8, syncDirty = false))
        var sentBody = ""
        val coordinator = coordinatorReturning { request ->
            sentBody = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            response(body = emptySyncResponse(1))
        }

        assertTrue(coordinator.syncOnce("https://tasks.example.com") is SyncRunResult.Success)

        val repairedProject = db.projectDao().get(project.uuid)!!
        assertEquals("HOME", repairedProject.name)
        assertEquals("home", repairedProject.nameNormalized)
        assertTrue(repairedProject.syncDirty)
        val repairedTasks = db.taskDao().pendingTasks(project.uuid)
        assertEquals(listOf(1, 2), repairedTasks.map { it.sortOrder })
        assertTrue(repairedTasks.all { it.syncDirty })
        assertTrue(sentBody.contains("\"name_normalized\":\"home\""))
    }

    @Test
    fun invalidSnapshotPersistsIssueAndSkipsNetwork() = runBlocking {
        db.syncStateDao().set(SyncStateEntity(SyncExporter.CHECKPOINT_KEY, "-1"))
        var calls = 0
        val coordinator = coordinatorReturning {
            calls++
            response(body = emptySyncResponse(1))
        }

        assertTrue(coordinator.syncOnce("https://tasks.example.com") is SyncRunResult.LocalSnapshotInvalid)
        assertEquals(0, calls)
        assertEquals("-1", db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
        assertEquals(SyncIssueKind.INVALID_SNAPSHOT.name, db.syncIssueDao().get()!!.kind)
    }

    @Test
    fun malformedJson_preservesCheckpointAndRollsBack() = runBlocking {
        db.syncStateDao().set(SyncStateEntity(SyncExporter.CHECKPOINT_KEY, "7"))
        val project = repository.createProject("Home")
        val coordinator = coordinatorReturning { response(body = "not-json") }

        val result = coordinator.syncOnce("https://tasks.example.com")

        assertTrue(result is SyncRunResult.MalformedJsonFailure)
        assertEquals("7", db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
        assertTrue(db.projectDao().get(project.uuid)!!.syncDirty)
    }

    @Test
    fun staleEntityRevisionInResponse_rollsBackAndPreservesNewerLocalRow() = runBlocking {
        db.syncStateDao().set(SyncStateEntity(SyncExporter.CHECKPOINT_KEY, "7"))
        val project = repository.createProject("Home")
        db.projectDao().upsert(project.copy(syncRevision = 5, syncDirty = false))
        val coordinator = coordinatorReturning {
            response(
                body = """
                {
                  "checkpoint": 8,
                  "projects": [${projectJson(project.uuid, "Home", "home", 4, syncDirty = false)}],
                  "tasks": [],
                  "tombstones": []
                }
                """.trimIndent(),
            )
        }

        val result = coordinator.syncOnce("https://tasks.example.com")

        assertTrue(result is SyncRunResult.RolledBackInvalidResponse)
        assertEquals("7", db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
        assertEquals(5, db.projectDao().get(project.uuid)!!.syncRevision)
    }

    @Test
    fun invalidPendingSortOrderInResponse_rollsBackRowsAndCheckpoint() = runBlocking {
        db.syncStateDao().set(SyncStateEntity(SyncExporter.CHECKPOINT_KEY, "7"))
        val project = repository.createProject("Home")
        val coordinator = coordinatorReturning {
            response(
                body = """
                {
                  "checkpoint": 8,
                  "projects": [${projectJson(project.uuid, "Home", "home", 1, syncDirty = false)}],
                  "tasks": [${taskJson("task-1", project.uuid, "Bad", "", 2147483648, 1, syncDirty = false)}],
                  "tombstones": []
                }
                """.trimIndent(),
            )
        }

        val result = coordinator.syncOnce("https://tasks.example.com")

        assertTrue(result is SyncRunResult.RolledBackInvalidResponse)
        assertEquals("7", db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
        assertNull(db.taskDao().get("task-1"))
    }

    @Test
    fun duplicateProjectNamesInResponse_rollBackWithoutMovingTasks() = runBlocking {
        db.syncStateDao().set(SyncStateEntity(SyncExporter.CHECKPOINT_KEY, "7"))
        val project = repository.createProject("Home")
        val task = repository.createTask(project.uuid, "Read", "")
        val coordinator = coordinatorReturning {
            response(
                body = """
                {
                  "checkpoint": 8,
                  "projects": [${projectJson("remote-1", "Home", "home", 1, syncDirty = false)}, ${projectJson("remote-2", " HOME", "home", 2, syncDirty = false)}],
                  "tasks": [],
                  "tombstones": []
                }
                """.trimIndent(),
            )
        }

        val result = coordinator.syncOnce("https://tasks.example.com")

        assertTrue(result is SyncRunResult.RolledBackInvalidResponse)
        assertEquals("7", db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
        assertNotNull(db.projectDao().get(project.uuid))
        assertEquals(project.uuid, db.taskDao().get(task.uuid)!!.projectUuid)
    }

    @Test
    fun missingProjectInResponse_rollsBackRowsAndCheckpoint() = runBlocking {
        db.syncStateDao().set(SyncStateEntity(SyncExporter.CHECKPOINT_KEY, "7"))
        val coordinator = coordinatorReturning {
            response(
                body = """
                {
                  "checkpoint": 8,
                  "projects": [],
                  "tasks": [${taskJson("task-1", "missing-project", "Bad", "", 1, 1, syncDirty = false)}],
                  "tombstones": []
                }
                """.trimIndent(),
            )
        }

        val result = coordinator.syncOnce("https://tasks.example.com")

        assertTrue(result is SyncRunResult.RolledBackInvalidResponse)
        assertEquals("7", db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
        assertNull(db.taskDao().get("task-1"))
    }

    @Test
    fun concurrentSyncRequests_runOneAtATime() = runBlocking {
        val firstRequestStarted = CompletableDeferred<Unit>()
        val allowFirstResponse = CompletableDeferred<Unit>()
        val requestCount = AtomicInteger(0)
        val coordinator = coordinatorReturning {
            val count = requestCount.incrementAndGet()
            if (count == 1) {
                firstRequestStarted.complete(Unit)
                runBlocking { allowFirstResponse.await() }
            }
            response(body = emptySyncResponse(checkpoint = count.toLong()))
        }

        val first = async(Dispatchers.Default) { coordinator.syncOnce("https://tasks.example.com") }
        firstRequestStarted.await()
        val second = async(Dispatchers.Default) { coordinator.syncOnce("https://tasks.example.com") }

        delay(150)
        assertEquals(1, requestCount.get())
        assertFalse(second.isCompleted)

        allowFirstResponse.complete(Unit)
        assertTrue(first.await() is SyncRunResult.Success)
        assertTrue(second.await() is SyncRunResult.Success)
        assertEquals(2, requestCount.get())
        assertEquals("2", db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
    }

    @Test
    fun localWritesCanProceedWhileSyncNetworkRequestIsInFlight() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val allowResponse = CompletableDeferred<Unit>()
        val coordinator = coordinatorReturning {
            requestStarted.complete(Unit)
            runBlocking { allowResponse.await() }
            response(body = emptySyncResponse(checkpoint = 1))
        }

        val sync = async(Dispatchers.Default) { coordinator.syncOnce("https://tasks.example.com") }
        requestStarted.await()
        val write = async(Dispatchers.Default) { repository.createProject("Not blocked") }

        val created = write.await()
        assertNotNull(db.projectDao().get(created.uuid))

        allowResponse.complete(Unit)
        assertEquals(SyncRunResult.LocalStateChangedDuringSync, sync.await())
        assertNull(db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
    }

    @Test
    fun responseFromStaleExportDoesNotOverwriteLocalChangesOrAdvanceCheckpoint() = runBlocking {
        val project = repository.createProject("Home")
        val requestStarted = CompletableDeferred<Unit>()
        val responseReady = CompletableDeferred<Unit>()
        val coordinator = coordinatorReturning {
            requestStarted.complete(Unit)
            runBlocking { responseReady.await() }
            response(
                body = """
                {
                  "checkpoint": 1,
                  "projects": [${projectJson(project.uuid, "Home", "home", 1, syncDirty = false)}],
                  "tasks": [],
                  "tombstones": []
                }
                """.trimIndent(),
            )
        }

        val sync = async(Dispatchers.Default) { coordinator.syncOnce("https://tasks.example.com") }
        requestStarted.await()
        repository.renameProject(project.uuid, "House")
        responseReady.complete(Unit)

        assertEquals(SyncRunResult.LocalStateChangedDuringSync, sync.await())
        assertEquals("House", db.projectDao().get(project.uuid)!!.name)
        assertTrue(db.projectDao().get(project.uuid)!!.syncDirty)
        assertNull(db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
    }

    @Test
    fun firstSyncInboxUuidReconciliation_movesLocalChildTasksInOneTransaction() = runBlocking {
        val localInbox = repository.ensureInbox()
        val localTask = repository.createTask(localInbox.uuid, "Shared link")
        val serverInboxUuid = "server-inbox-uuid"
        val coordinator = coordinatorReturning {
            response(
                body = """
                {
                  "checkpoint": 1,
                  "projects": [${projectJson(serverInboxUuid, "Inbox", "inbox", 3, syncDirty = false)}],
                  "tasks": [],
                  "tombstones": []
                }
                """.trimIndent(),
            )
        }

        val result = coordinator.syncOnce("https://tasks.example.com")

        assertTrue(result is SyncRunResult.Success)
        assertNull(db.projectDao().get(localInbox.uuid))
        assertNotNull(db.projectDao().get(serverInboxUuid))
        val movedTask = db.taskDao().get(localTask.uuid)!!
        assertEquals(serverInboxUuid, movedTask.projectUuid)
        assertEquals(TaskStatus.Pending, movedTask.status)
        assertTrue(movedTask.syncDirty)
        assertEquals("1", db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
    }

    @Test
    fun responseReviewedAtOverwritesLocalValue() = runBlocking {
        val project = repository.createProject("Home")
        val task = repository.createTask(project.uuid, "Read")
        val coordinator = coordinatorReturning {
            response(
                body = """
                {
                  "checkpoint": 3,
                  "projects": [${projectJson(project.uuid, "Home", "home", 1, syncDirty = false)}],
                  "tasks": [${taskJson(task.uuid, project.uuid, "Read", "", 1, 2, syncDirty = false, reviewedAt = "2026-10-20T08:00:00Z")}],
                  "tombstones": []
                }
                """.trimIndent(),
            )
        }

        assertTrue(coordinator.syncOnce("https://tasks.example.com") is SyncRunResult.Success)
        assertEquals("2026-10-20T08:00:00Z", db.taskDao().get(task.uuid)!!.reviewedAt)
    }

    @Test
    fun responseWithoutReviewedAt_keepsLocalValueOrFallsBackToCreatedAt() = runBlocking {
        val project = repository.createProject("Home")
        val local = repository.createTask(project.uuid, "Local")
        val coordinator = coordinatorReturning {
            response(
                body = """
                {
                  "checkpoint": 3,
                  "projects": [${projectJson(project.uuid, "Home", "home", 1, syncDirty = false)}],
                  "tasks": [
                    ${taskJson(local.uuid, project.uuid, "Local", "", 1, 2, syncDirty = false, reviewedAt = null)},
                    ${taskJson("server-task", project.uuid, "Remote", "", 2, 2, syncDirty = false, reviewedAt = null)}
                  ],
                  "tombstones": []
                }
                """.trimIndent(),
            )
        }

        assertTrue(coordinator.syncOnce("https://tasks.example.com") is SyncRunResult.Success)
        assertEquals(local.reviewedAt, db.taskDao().get(local.uuid)!!.reviewedAt)
        assertEquals("2026-04-27T10:03:00Z", db.taskDao().get("server-task")!!.reviewedAt)
    }

    private fun coordinatorReturning(handler: (okhttp3.Request) -> Response): SyncCoordinator = SyncCoordinator(
        db = db,
        transport = SyncTransport(
            OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain -> handler(chain.request()) })
                .build(),
        ),
    )

    private fun response(code: Int = 200, body: String): Response = Response.Builder()
        .request(okhttp3.Request.Builder().url("https://tasks.example.com/sync").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("OK")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun emptySyncResponse(checkpoint: Long): String = """
        {
          "checkpoint": $checkpoint,
          "projects": [],
          "tasks": [],
          "tombstones": []
        }
    """.trimIndent()

    private fun projectJson(
        uuid: String,
        name: String,
        normalized: String,
        revision: Long,
        syncDirty: Boolean,
    ): String = """
        {
          "uuid": "$uuid",
          "name": "$name",
          "name_normalized": "$normalized",
          "created_at": "2026-04-27T10:03:00Z",
          "updated_at": "2026-04-27T10:03:00Z",
          "archived_at": null,
          "sync_revision": $revision,
          "sync_dirty": $syncDirty
        }
    """.trimIndent()

    private fun taskJson(
        uuid: String,
        projectUuid: String,
        title: String,
        notes: String,
        sortOrder: Long,
        revision: Long,
        syncDirty: Boolean,
        reviewedAt: String? = "2026-04-27T10:03:00Z",
    ): String = """
        {
          "uuid": "$uuid",
          "project_uuid": "$projectUuid",
          "sort_order": $sortOrder,
          "title": "$title",
          "notes": "$notes",
          "status": "pending",
          "created_at": "2026-04-27T10:03:00Z",
          "updated_at": "2026-04-27T10:03:00Z",
          "completed_at": null,
          "sync_revision": $revision,
          "sync_dirty": $syncDirty${reviewedAt?.let { ",\n  \"reviewed_at\": \"$it\"" }.orEmpty()}
        }
    """.trimIndent()
}
