package com.couchtasks.android.ui.settings

import android.app.AlarmManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Whether exact alarms are allowed, re-checked when Settings resumes (e.g. back from the system page). */
@Composable
fun rememberExactAlarmsAllowed(): Boolean {
    val context = LocalContext.current
    val alarmManager = remember(context) { context.getSystemService(AlarmManager::class.java) }
    var allowed by remember { mutableStateOf(alarmManager.canScheduleExactAlarms()) }
    LifecycleResumeEffect(alarmManager) {
        allowed = alarmManager.canScheduleExactAlarms()
        onPauseOrDispose {}
    }
    return allowed
}

/** Suggests allowing exact alarms so reminders arrive on time; reminders still work, possibly late, without them. */
@Composable
fun ExactAlarmCard(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ExactAlarmCard"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("On-time reminders", fontWeight = FontWeight.SemiBold)
            Text(
                "Reminders may arrive up to 10 minutes late, or later while the phone is idle. Allow alarms and " +
                    "reminders for Couch Tasks to get them on time.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()),
                        )
                    },
                ) { Text("Allow") }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("ExactAlarmDismiss")) { Text("Not now") }
            }
        }
    }
}
