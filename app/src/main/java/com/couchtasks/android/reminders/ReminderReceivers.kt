package com.couchtasks.android.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.couchtasks.android.CouchTasksApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The scheduler's own alarm: post what is due and arm the next one. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runPass(context, suppressMissed = false)
}

/** System events that clear or skew alarms. Reboots and clock changes skip reminders that came due meanwhile. */
class ReminderSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val suppressMissed = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED -> true
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> false
            else -> return
        }
        runPass(context, suppressMissed)
    }
}

private fun BroadcastReceiver.runPass(context: Context, suppressMissed: Boolean) {
    val pending = goAsync()
    val scheduler = (context.applicationContext as CouchTasksApplication).appContainer.reminderScheduler
    CoroutineScope(Dispatchers.IO).launch {
        try {
            scheduler.reschedule(suppressMissed)
        } finally {
            pending.finish()
        }
    }
}
