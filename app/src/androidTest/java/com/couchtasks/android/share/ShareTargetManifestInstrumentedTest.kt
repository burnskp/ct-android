package com.couchtasks.android.share

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.couchtasks.android.CouchTasksApplication
import com.couchtasks.android.data.TaskStatus
import com.couchtasks.android.data.TasksRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTargetManifestInstrumentedTest {
    @Test
    fun appResolvesTextPlainSendIntentToShareReceiver() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://example.com")
            .setPackage(context.packageName)

        val matches = context.packageManager.queryIntentActivitiesCompat(intent)

        assertTrue(
            "Expected ShareReceiverActivity to resolve ACTION_SEND text/plain",
            matches.any { it.activityInfo.name == ShareReceiverActivity::class.java.name && it.activityInfo.exported },
        )
    }

    @Test
    fun browserShareCreatesPendingDirtyInboxTaskWithUrlInNotes() {
        val app = ApplicationProvider.getApplicationContext<CouchTasksApplication>()
        runBlocking { app.appContainer.database.clearAllTables() }
        val intent = Intent(app, ShareReceiverActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "Example page")
            .putExtra(Intent.EXTRA_TEXT, "Read this: https://www.example.com/article")

        ActivityScenario.launch<ShareReceiverActivity>(intent).use {
            waitUntilTaskCreated(app)
        }

        runBlocking {
            val inbox = app.appContainer.database.projectDao().getByNormalizedName(TasksRepository.INBOX_NORMALIZED)
            assertNotNull(inbox)
            val tasks = app.appContainer.database.taskDao().tasksForProject(inbox!!.uuid)
            assertEquals(1, tasks.size)
            val task = tasks.single()
            assertEquals("Example page", task.title)
            assertEquals("https://www.example.com/article", task.notes)
            assertEquals(TaskStatus.Pending, task.status)
            assertEquals(1, task.sortOrder)
            assertEquals(0, task.syncRevision)
            assertTrue(task.syncDirty)
        }
    }

    private fun waitUntilTaskCreated(app: CouchTasksApplication) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val created = runBlocking {
                val inbox = app.appContainer.database.projectDao().getByNormalizedName(TasksRepository.INBOX_NORMALIZED)
                inbox != null && app.appContainer.database.taskDao().tasksForProject(inbox.uuid).isNotEmpty()
            }
            if (created) return
            SystemClock.sleep(50)
        }
        error("Timed out waiting for share task creation")
    }

    private fun PackageManager.queryIntentActivitiesCompat(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, 0)
        }
}
