package com.couchtasks.android.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.couchtasks.android.MainActivity
import com.couchtasks.android.R
import com.couchtasks.android.data.CouchTasksDatabase
import com.couchtasks.android.data.ReminderCandidate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Runs reminder passes: applies [ReminderPlanner] to the database, posts due notifications, and keeps one alarm set
 * for the next reminder. Every trigger (alarm, data change, app start, boot, clock change) just runs another pass.
 */
class ReminderScheduler(
    context: Context,
    private val db: CouchTasksDatabase,
    private val now: () -> Instant = Instant::now,
) {
    private val context = context.applicationContext
    private val alarmManager = this.context.getSystemService(AlarmManager::class.java)
    private val notifications = NotificationManagerCompat.from(this.context)
    private val mutex = Mutex()

    /** [suppressMissed] after a reboot or clock change, so reminders that came due meanwhile stay quiet. */
    suspend fun reschedule(suppressMissed: Boolean = false) = mutex.withLock {
        val dao = db.reminderDao()
        val plan = db.withTransaction {
            ReminderPlanner.plan(dao.candidates(), dao.states(), now(), suppressMissed).also { plan ->
                dao.deleteStates(plan.delete)
                dao.upsertStates(plan.upsert)
            }
        }
        // A shown reminder whose task is done, deleted, or re-dated is stale.
        plan.delete.filter { it.notified }.forEach { notifications.cancel(it.taskUuid, NOTIFICATION_ID) }
        post(plan.notify)
        setAlarm(plan.nextAlarm)
    }

    fun canScheduleExactAlarms(): Boolean = alarmManager.canScheduleExactAlarms()

    private fun post(due: List<ReminderCandidate>) {
        if (due.isEmpty() || !canPostNotifications()) return
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Reminders")
                .setDescription("Task reminders at their due time")
                .build(),
        )
        try {
            due.forEach { reminder ->
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(reminder.title)
                    .setContentText(reminder.projectName)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setWhen(Instant.parse(reminder.dueAt).toEpochMilli())
                    .setShowWhen(true)
                    .setGroup(GROUP_KEY)
                    .setAutoCancel(true)
                    .setContentIntent(openTaskIntent(reminder))
                    .build()
                notifications.notify(reminder.taskUuid, NOTIFICATION_ID, notification)
            }
            val summary = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Reminders")
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()
            notifications.notify(SUMMARY_ID, summary)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post; the reminder is already marked notified.
        }
    }

    private fun canPostNotifications(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            notifications.areNotificationsEnabled()

    private fun openTaskIntent(reminder: ReminderCandidate): PendingIntent = PendingIntent.getActivity(
        context,
        reminder.taskUuid.hashCode(),
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PROJECT_UUID, reminder.projectUuid)
            .putExtra(MainActivity.EXTRA_TASK_UUID, reminder.taskUuid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setAlarm(at: Instant?) {
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            alarmManager.cancel(intent)
            return
        }
        val millis = at.toEpochMilli()
        // Exact alarms can be revoked at any time; fall back rather than fail. A plain inexact alarm's window grows
        // with its delay (hours for a reminder days away), which would push it past ReminderPlanner.GRACE, so the
        // fallback is a bounded window. It can still wait for a maintenance window while the device is idle.
        val exact = canScheduleExactAlarms() && runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        }.isSuccess
        if (!exact) alarmManager.setWindow(AlarmManager.RTC_WAKEUP, millis, FALLBACK_WINDOW_MILLIS, intent)
    }

    private companion object {
        const val CHANNEL_ID = "reminders"
        const val GROUP_KEY = "com.couchtasks.android.REMINDERS"
        const val NOTIFICATION_ID = 1
        const val SUMMARY_ID = 2
        const val FALLBACK_WINDOW_MILLIS = 10 * 60 * 1000L
    }
}
