package com.couchtasks.android.ui.tasks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.couchtasks.android.data.DueDates
import com.couchtasks.android.data.DueFormat
import com.couchtasks.android.data.TaskEntity
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The user's due display format, provided once at the top of the app. */
val LocalDueFormat = staticCompositionLocalOf { DueFormat.Absolute }

/** "Now" for due display, ticking on each wall-clock minute so relative values and overdue colour stay current. */
val LocalNow = compositionLocalOf { Instant.now() }

@Composable
fun rememberMinuteClock(): State<Instant> = produceState(Instant.now()) {
    while (true) {
        delay(60_000 - System.currentTimeMillis() % 60_000)
        value = Instant.now()
    }
}

/** Due value and colour for [task] in the current format, or null when it has no due date. */
@Composable
fun dueText(task: TaskEntity): Pair<String, Boolean>? {
    val dueAt = task.dueAt ?: return null
    val now = LocalNow.current
    val text = DueDates.display(dueAt, task.status, LocalDueFormat.current, now, ZoneId.systemDefault())
    return text to DueDates.isOverdue(dueAt, task.status, now)
}

@Composable
fun overdueColor(overdue: Boolean): Color =
    if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

/** Due chip and reminder bell for list rows. Overdue is red, with "overdue" spoken for screen readers. */
@Composable
fun DueChips(task: TaskEntity, modifier: Modifier = Modifier) {
    val (text, overdue) = dueText(task) ?: return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .testTag("DueChip-${task.uuid}")
                .clearAndSetSemantics { contentDescription = if (overdue) "overdue, due $text" else "due $text" },
        ) {
            Text(
                "due $text",
                style = MaterialTheme.typography.labelMedium,
                color = overdueColor(overdue),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (task.remind) {
            Text(
                "🔔",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .testTag("RemindIndicator-${task.uuid}")
                    .clearAndSetSemantics { contentDescription = "reminder on" },
            )
        }
    }
}

/** Material date picker; dates are exchanged as local calendar dates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueDatePickerDialog(initial: LocalDate?, onConfirm: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // The picker works in UTC millis at midnight; using UTC both ways keeps the calendar date unchanged.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onConfirm(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

/** 24-hour time picker, matching the CLI's `HH:MM`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueTimePickerDialog(initial: LocalTime, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}

private fun notificationsAllowed(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

/** Whether a reminder can alert on this device, re-checked whenever the screen resumes (e.g. back from Settings). */
@Composable
fun rememberNotificationsAllowed(): Boolean {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleResumeEffect(context) {
        allowed = notificationsAllowed(context)
        onPauseOrDispose {}
    }
    return allowed
}

/** Asks for notification permission, if not yet granted, when the user first turns a reminder on. Never blocks saving. */
@Composable
fun rememberRemindChange(onRemindChange: (Boolean) -> Unit): (Boolean) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return { remind ->
        onRemindChange(remind)
        if (remind && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
