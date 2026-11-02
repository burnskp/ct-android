package com.couchtasks.android.reminders

import com.couchtasks.android.data.ReminderCandidate
import com.couchtasks.android.data.ReminderStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ReminderPlannerTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")

    private fun candidate(uuid: String, dueAt: String) = ReminderCandidate(uuid, "project", "Task $uuid", "Inbox", dueAt)
    private fun armed(uuid: String, dueAt: String) = ReminderStateEntity(uuid, dueAt, notified = false)
    private fun notified(uuid: String, dueAt: String) = ReminderStateEntity(uuid, dueAt, notified = true)

    @Test
    fun futureReminders_areArmedAndTheEarliestSetsTheAlarm() {
        val plan = ReminderPlanner.plan(
            listOf(candidate("a", "2026-11-03T09:00:00Z"), candidate("b", "2026-11-02T13:00:00Z")),
            listOf(armed("a", "2026-11-03T09:00:00Z")),
            now,
            suppressMissed = false,
        )

        assertEquals(listOf(armed("b", "2026-11-02T13:00:00Z")), plan.upsert)
        assertEquals(Instant.parse("2026-11-02T13:00:00Z"), plan.nextAlarm)
        assertEquals(emptyList<ReminderCandidate>(), plan.notify)
    }

    @Test
    fun armedReminderDueWithinTheHour_isPostedOnce() {
        val due = "2026-11-02T11:00:00Z"
        val plan = ReminderPlanner.plan(listOf(candidate("a", due)), listOf(armed("a", due)), now, suppressMissed = false)

        assertEquals(listOf(candidate("a", due)), plan.notify)
        assertEquals(listOf(notified("a", due)), plan.upsert)
        assertNull(plan.nextAlarm)

        val again = ReminderPlanner.plan(listOf(candidate("a", due)), plan.upsert, now, suppressMissed = false)
        assertEquals(emptyList<ReminderCandidate>(), again.notify)
        assertEquals(emptyList<ReminderStateEntity>(), again.upsert)
    }

    @Test
    fun armedReminderMoreThanAnHourLate_isMarkedSilently() {
        val due = "2026-11-02T10:59:59Z"
        val plan = ReminderPlanner.plan(listOf(candidate("a", due)), listOf(armed("a", due)), now, suppressMissed = false)

        assertEquals(emptyList<ReminderCandidate>(), plan.notify)
        assertEquals(listOf(notified("a", due)), plan.upsert)
    }

    @Test
    fun afterRebootOrClockChange_missedRemindersAreMarkedSilently() {
        val due = "2026-11-02T11:59:00Z"
        val plan = ReminderPlanner.plan(listOf(candidate("a", due)), listOf(armed("a", due)), now, suppressMissed = true)

        assertEquals(emptyList<ReminderCandidate>(), plan.notify)
        assertEquals(listOf(notified("a", due)), plan.upsert)
    }

    @Test
    fun reminderFirstSeenAlreadyPast_isMarkedSilently() {
        val due = "2026-11-02T11:59:00Z"
        val plan = ReminderPlanner.plan(listOf(candidate("a", due)), emptyList(), now, suppressMissed = false)

        assertEquals(emptyList<ReminderCandidate>(), plan.notify)
        assertEquals(listOf(notified("a", due)), plan.upsert)
    }

    @Test
    fun reminderDueExactlyNow_isPosted() {
        val due = "2026-11-02T12:00:00Z"
        val plan = ReminderPlanner.plan(listOf(candidate("a", due)), listOf(armed("a", due)), now, suppressMissed = false)

        assertEquals(listOf(candidate("a", due)), plan.notify)
    }

    @Test
    fun changedDueTime_reArmsAndDropsTheOldState() {
        val oldDue = "2026-11-02T11:00:00Z"
        val newDue = "2026-11-02T15:00:00Z"
        val plan = ReminderPlanner.plan(listOf(candidate("a", newDue)), listOf(notified("a", oldDue)), now, suppressMissed = false)

        assertEquals(listOf(armed("a", newDue)), plan.upsert)
        assertEquals(listOf(notified("a", oldDue)), plan.delete)
        assertEquals(Instant.parse(newDue), plan.nextAlarm)
    }

    @Test
    fun doneDeletedOrClearedReminders_dropTheirState() {
        val states = listOf(armed("gone", "2026-11-03T09:00:00Z"), notified("old", "2026-11-01T09:00:00Z"))
        val plan = ReminderPlanner.plan(emptyList(), states, now, suppressMissed = false)

        assertEquals(states, plan.delete)
        assertNull(plan.nextAlarm)
    }
}
