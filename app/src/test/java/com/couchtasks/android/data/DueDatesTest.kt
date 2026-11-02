package com.couchtasks.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Vectors from the due-dates handoff and the CLI's `src/due.rs` tests. */
class DueDatesTest {
    private val minusFive = ZoneOffset.ofHours(-5)
    private val parseNow = LocalDateTime.of(2026, 11, 2, 15, 30).atOffset(minusFive).toInstant()
    private val newYork = ZoneId.of("America/New_York")
    private val displayNow = Instant.parse("2026-11-02T12:00:00Z")

    @Test
    fun parse_acceptsEveryInputForm() {
        mapOf(
            "2026-11-15" to "2026-11-15T14:00:00Z",
            "27-01-05" to "2027-01-05T14:00:00Z",
            "12-25" to "2026-12-25T14:00:00Z",
            "1-5" to "2026-01-05T14:00:00Z",
            "15" to "2026-11-15T14:00:00Z",
            "1" to "2026-11-01T14:00:00Z",
            "4d" to "2026-11-06T14:00:00Z",
            "1w" to "2026-11-09T14:00:00Z",
            "2W3D" to "2026-11-19T14:00:00Z",
            "0d" to "2026-11-02T14:00:00Z",
            "15 14:30" to "2026-11-15T19:30:00Z",
            "2w 8:05" to "2026-11-16T13:05:00Z",
            "2026-11-15 23:59" to "2026-11-16T04:59:00Z",
            "  05-07\t 9:00 " to "2026-05-07T14:00:00Z",
            "007d" to "2026-11-09T14:00:00Z",
            "0w0d" to "2026-11-02T14:00:00Z",
        ).forEach { (input, expected) ->
            assertEquals(input, expected, DueDates.parse(input, parseNow, minusFive))
        }
    }

    @Test
    fun parse_rejectsInvalidInput() {
        listOf(
            "", "3d2w", "2w3", "w", "d", "11/15", "2-30", "31", "026-11-15", "2026-1-123", "15 24:00", "15 9:5",
            "15 2pm", "15 14:30 extra", "-1d", "today", "+3d", "4294967296d",
        ).forEach { input ->
            val error = assertThrows(input, IllegalArgumentException::class.java) {
                DueDates.parse(input, parseNow, minusFive)
            }
            assertEquals(
                "Invalid due date: $input (expected YYYY-MM-DD, YY-MM-DD, MM-DD, DD, or <n>w<n>d, optionally followed by HH:MM)",
                error.message,
            )
        }
    }

    @Test
    fun parse_takesEarlierInstantInFallBackOverlap() {
        assertEquals("2026-11-01T05:30:00Z", DueDates.parse("2026-11-01 01:30", parseNow, newYork))
    }

    @Test
    fun parse_rejectsSpringForwardGap() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            DueDates.parse("2026-03-08 02:30", parseNow, newYork)
        }
        assertEquals("Due time does not exist in local time: 2026-03-08 02:30", error.message)
    }

    @Test
    fun parse_countsRelativeDaysOnTheLocalCalendarAcrossDst() {
        val beforeFallBack = LocalDateTime.of(2026, 10, 31, 12, 0).atZone(newYork).toInstant()
        assertEquals("2026-11-02T14:00:00Z", DueDates.parse("2d", beforeFallBack, newYork))
    }

    @Test
    fun formatRelative_usesCliBands() {
        mapOf(
            "2026-11-19T15:00:00Z" to "2w3d",
            "2026-11-16T12:00:00Z" to "2w",
            "2026-11-09T12:00:00Z" to "1w",
            "2026-11-09T11:59:00Z" to "6d",
            "2026-11-06T23:00:00Z" to "4d",
            "2026-11-05T12:00:00Z" to "3d",
            "2026-11-05T11:00:00Z" to "2d23h",
            "2026-11-03T12:30:00Z" to "1d",
            "2026-11-03T11:59:00Z" to "23h59m",
            "2026-11-03T05:30:00Z" to "17h30m",
            "2026-11-02T15:00:00Z" to "3h",
            "2026-11-02T12:45:00Z" to "45m",
            "2026-11-02T12:00:00Z" to "0m",
            "2026-10-31T07:00:00Z" to "2d5h",
            "2026-11-02T11:59:30Z" to "0m",
        ).forEach { (dueAt, expected) ->
            assertEquals(dueAt, expected, DueDates.formatRelative(dueAt, displayNow))
        }
    }

    @Test
    fun formatAbsolute_usesLocalTime() {
        assertEquals("2026-10-30 09:00", DueDates.formatAbsolute("2026-10-30T09:00:00Z", ZoneOffset.UTC))
        assertEquals("2026-11-15 09:00", DueDates.formatAbsolute("2026-11-15T14:00:00Z", minusFive))
    }

    @Test
    fun nonCanonicalStoredValues_areShownRawAndNeverOverdue() {
        assertEquals("2026-12-01", DueDates.formatAbsolute("2026-12-01", ZoneOffset.UTC))
        assertEquals("2026-12-01", DueDates.formatRelative("2026-12-01", displayNow))
        assertFalse(DueDates.isOverdue("2020-01-01", TaskStatus.Pending, displayNow))
    }

    @Test
    fun isOverdue_onlyForPendingTasksStrictlyBeforeNow() {
        assertTrue(DueDates.isOverdue("2026-10-30T09:00:00Z", TaskStatus.Pending, displayNow))
        assertTrue(DueDates.isOverdue("2026-11-02T11:59:59Z", TaskStatus.Pending, displayNow))
        assertFalse(DueDates.isOverdue("2026-10-30T09:00:00Z", TaskStatus.Done, displayNow))
        assertFalse(DueDates.isOverdue("2026-11-02T12:00:00Z", TaskStatus.Pending, displayNow))
        assertFalse(DueDates.isOverdue(null, TaskStatus.Pending, displayNow))
    }

    @Test
    fun display_usesChosenFormatForPendingAndAbsoluteForDone() {
        val due = "2026-10-31T07:00:00Z"
        assertEquals("2d5h", DueDates.display(due, TaskStatus.Pending, DueFormat.Relative, displayNow, ZoneOffset.UTC))
        assertEquals("2026-10-31 07:00", DueDates.display(due, TaskStatus.Pending, DueFormat.Absolute, displayNow, ZoneOffset.UTC))
        assertEquals("2026-10-31 07:00", DueDates.display(due, TaskStatus.Done, DueFormat.Relative, displayNow, ZoneOffset.UTC))
    }

    @Test
    fun toLocal_convertsToZoneAndIgnoresMissingOrInvalidValues() {
        assertEquals(LocalDateTime.of(2026, 11, 15, 9, 0), DueDates.toLocal("2026-11-15T14:00:00Z", minusFive))
        assertEquals(null, DueDates.toLocal(null, minusFive))
        assertEquals(null, DueDates.toLocal("2026-11-15", minusFive))
    }

    @Test
    fun isCanonical_acceptsOnlyUtcWholeSeconds() {
        assertTrue(DueDates.isCanonical("2026-11-15T14:00:00Z"))
        listOf(
            "2026-11-15T09:00:00-05:00", "2026-11-15T14:00:00.000Z", "2026-12-01", "2026-11-15t14:00:00z",
            "2026-11-15 14:00:00Z", "2026-02-30T14:00:00Z", "2026-11-15T23:59:60Z",
        ).forEach { assertFalse(it, DueDates.isCanonical(it)) }
    }

    @Test
    fun normalizeDue_dropsReminderWithoutDueDateAndRejectsNonCanonical() {
        assertEquals(null to false, normalizeDue(null, remind = true))
        assertEquals("2026-11-15T14:00:00Z" to true, normalizeDue("2026-11-15T14:00:00Z", remind = true))
        assertThrows(IllegalArgumentException::class.java) { normalizeDue("2026-11-15", remind = false) }
    }
}
