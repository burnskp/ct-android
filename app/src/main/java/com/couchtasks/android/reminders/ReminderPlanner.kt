package com.couchtasks.android.reminders

import com.couchtasks.android.data.DueDates
import com.couchtasks.android.data.ReminderCandidate
import com.couchtasks.android.data.ReminderStateEntity
import java.time.Duration
import java.time.Instant

/** What one scheduling pass does: notifications to post, state to write or drop, and when to wake next. */
data class ReminderPlan(
    val notify: List<ReminderCandidate>,
    val upsert: List<ReminderStateEntity>,
    val delete: List<ReminderStateEntity>,
    val nextAlarm: Instant?,
)

/**
 * Decides reminders without touching Android. A reminder (task + due time) is armed once a pass sees it in the future.
 * Once due, it is posted only if it was armed, is at most [GRACE] late, and the pass is not [suppressMissed] (after a
 * reboot or clock change). Anything else past due is marked notified silently: due dates that arrive already past, by
 * sync or by editing, never notify. Changing the due time makes a new reminder, so it re-arms.
 */
object ReminderPlanner {
    val GRACE: Duration = Duration.ofHours(1)

    fun plan(
        candidates: List<ReminderCandidate>,
        states: List<ReminderStateEntity>,
        now: Instant,
        suppressMissed: Boolean,
    ): ReminderPlan {
        val stateByKey = states.associateBy { it.taskUuid to it.dueAt }
        val notify = mutableListOf<ReminderCandidate>()
        val upsert = mutableListOf<ReminderStateEntity>()
        var nextAlarm: Instant? = null
        candidates.filter { DueDates.isCanonical(it.dueAt) }.forEach { candidate ->
            val dueAt = Instant.parse(candidate.dueAt)
            val state = stateByKey[candidate.taskUuid to candidate.dueAt]
            if (state?.notified == true) return@forEach
            if (dueAt.isAfter(now)) {
                if (state == null) upsert += ReminderStateEntity(candidate.taskUuid, candidate.dueAt, notified = false)
                if (nextAlarm == null || dueAt.isBefore(nextAlarm)) nextAlarm = dueAt
                return@forEach
            }
            val armed = state != null
            if (armed && !suppressMissed && !dueAt.isBefore(now.minus(GRACE))) notify += candidate
            upsert += ReminderStateEntity(candidate.taskUuid, candidate.dueAt, notified = true)
        }
        val live = candidates.mapTo(mutableSetOf()) { it.taskUuid to it.dueAt }
        val delete = states.filter { (it.taskUuid to it.dueAt) !in live }
        return ReminderPlan(notify, upsert, delete, nextAlarm)
    }
}
