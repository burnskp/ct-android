package com.couchtasks.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskStatusTest {
    @Test
    fun normalizeProjectName_trimsAndLowercases() {
        assertEquals("inbox", normalizeProjectName("  InBoX  "))
    }

    @Test
    fun projectName_requiresNonBlankTrimmedName() {
        assertEquals("Home", requireProjectName("  Home  "))
        assertThrows(IllegalArgumentException::class.java) { requireProjectName("   ") }
    }

    @Test
    fun taskTitle_requiresNonBlankTrimmedTitle() {
        assertEquals("Buy milk", requireTaskTitle("  Buy milk  "))
        assertThrows(IllegalArgumentException::class.java) { requireTaskTitle("\t") }
    }

    @Test
    fun taskStatus_acceptsOnlyPendingOrDone() {
        assertEquals(TaskStatus.Pending, TaskStatus.requireValid("pending"))
        assertEquals(TaskStatus.Done, TaskStatus.requireValid("done"))
        assertThrows(IllegalArgumentException::class.java) { TaskStatus.requireValid("archived") }
    }

    @Test
    fun transitionTaskStatus_completeSetsDoneTimestampAndCompactionFlag() {
        val transition = transitionTaskStatus(
            currentStatus = TaskStatus.Pending,
            requestedStatus = TaskStatus.Done,
            currentPendingSortOrder = 3,
            currentCompletedAt = null,
            appendedPendingSortOrder = 0,
            now = "2026-01-02T03:04:05Z",
        )

        assertEquals(TaskStatus.Done, transition.status)
        assertEquals(0, transition.sortOrder)
        assertEquals("2026-01-02T03:04:05Z", transition.completedAt)
        assertEquals(true, transition.pendingOrderNeedsCompaction)
    }

    @Test
    fun transitionTaskStatus_reopenAppendsToPendingAndClearsCompletedAt() {
        val transition = transitionTaskStatus(
            currentStatus = TaskStatus.Done,
            requestedStatus = TaskStatus.Pending,
            currentPendingSortOrder = 0,
            currentCompletedAt = "2026-01-02T03:04:05Z",
            appendedPendingSortOrder = 4,
            now = "2026-01-03T03:04:05Z",
        )

        assertEquals(TaskStatus.Pending, transition.status)
        assertEquals(4, transition.sortOrder)
        assertEquals(null, transition.completedAt)
        assertEquals(false, transition.pendingOrderNeedsCompaction)
    }

    @Test
    fun transitionTaskStatus_doneToDonePreservesCompletedAt() {
        val transition = transitionTaskStatus(
            currentStatus = TaskStatus.Done,
            requestedStatus = TaskStatus.Done,
            currentPendingSortOrder = 0,
            currentCompletedAt = "2026-01-02T03:04:05Z",
            appendedPendingSortOrder = 0,
            now = "2026-01-03T03:04:05Z",
        )

        assertEquals(TaskStatus.Done, transition.status)
        assertEquals(0, transition.sortOrder)
        assertEquals("2026-01-02T03:04:05Z", transition.completedAt)
        assertEquals(false, transition.pendingOrderNeedsCompaction)
    }
}
