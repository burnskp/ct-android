package com.couchtasks.android.reminders

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.couchtasks.android.data.CouchTasksDatabase
import com.couchtasks.android.data.ReminderStateEntity
import com.couchtasks.android.data.TasksRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ReminderSchedulerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var db: CouchTasksDatabase
    private lateinit var repository: TasksRepository
    private lateinit var notifications: NotificationManager
    private var now = Instant.parse("2026-11-02T12:00:00Z")
    private lateinit var scheduler: ReminderScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        db = Room.inMemoryDatabaseBuilder(context, CouchTasksDatabase::class.java).allowMainThreadQueries().build()
        repository = TasksRepository(db)
        notifications = context.getSystemService(NotificationManager::class.java)
        notifications.cancelAll()
        scheduler = ReminderScheduler(context, db) { now }
    }

    @After
    fun tearDown() {
        notifications.cancelAll()
        db.close()
    }

    @Test
    fun armedReminder_postsAtDueTimeAndIsCancelledWhenTheTaskIsDone() = runBlocking {
        val inbox = repository.ensureInbox()
        val task = repository.createTask(inbox.uuid, "Call dentist", dueAt = "2026-11-02T13:00:00Z", remind = true)

        scheduler.reschedule()
        assertEquals(listOf(ReminderStateEntity(task.uuid, "2026-11-02T13:00:00Z", notified = false)), db.reminderDao().states())
        assertFalse(isShown(task.uuid))

        now = Instant.parse("2026-11-02T13:00:05Z")
        scheduler.reschedule()
        assertTrue(isShown(task.uuid))
        val shown = notifications.activeNotifications.single { it.tag == task.uuid }.notification
        assertEquals("Call dentist", shown.extras.getString("android.title"))
        assertEquals("Inbox", shown.extras.getString("android.text"))

        repository.completeTask(task.uuid)
        scheduler.reschedule()
        assertFalse(isShown(task.uuid))
        assertEquals(emptyList<ReminderStateEntity>(), db.reminderDao().states())
    }

    @Test
    fun pastDueDateSetByEdit_staysQuiet() = runBlocking {
        val inbox = repository.ensureInbox()
        val task = repository.createTask(inbox.uuid, "Already late", dueAt = "2026-11-02T11:30:00Z", remind = true)

        scheduler.reschedule()

        assertFalse(isShown(task.uuid))
        assertEquals(listOf(ReminderStateEntity(task.uuid, "2026-11-02T11:30:00Z", notified = true)), db.reminderDao().states())
    }

    @Test
    fun rebootPass_skipsRemindersThatCameDueWhileOff() = runBlocking {
        val inbox = repository.ensureInbox()
        val task = repository.createTask(inbox.uuid, "Missed", dueAt = "2026-11-02T13:00:00Z", remind = true)
        scheduler.reschedule()

        now = Instant.parse("2026-11-02T13:40:00Z")
        scheduler.reschedule(suppressMissed = true)

        assertFalse(isShown(task.uuid))
        assertTrue(db.reminderDao().states().single().notified)
    }

    private fun isShown(taskUuid: String): Boolean = notifications.activeNotifications.any { it.tag == taskUuid }
}
