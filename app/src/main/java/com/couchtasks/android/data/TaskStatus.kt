package com.couchtasks.android.data

import java.util.Locale

object TaskStatus {
    const val Pending = "pending"
    const val Done = "done"

    fun requireValid(status: String): String {
        require(status == Pending || status == Done) { "Unsupported task status: $status" }
        return status
    }
}

data class TaskStatusTransition(
    val status: String,
    val sortOrder: Int,
    val completedAt: String?,
    val pendingOrderNeedsCompaction: Boolean,
)

fun transitionTaskStatus(
    currentStatus: String,
    requestedStatus: String,
    currentPendingSortOrder: Int,
    currentCompletedAt: String?,
    appendedPendingSortOrder: Int,
    now: String,
): TaskStatusTransition {
    val current = TaskStatus.requireValid(currentStatus)
    val requested = TaskStatus.requireValid(requestedStatus)
    return when {
        current == TaskStatus.Pending && requested == TaskStatus.Pending -> TaskStatusTransition(
            status = TaskStatus.Pending,
            sortOrder = currentPendingSortOrder,
            completedAt = null,
            pendingOrderNeedsCompaction = false,
        )
        current == TaskStatus.Pending && requested == TaskStatus.Done -> TaskStatusTransition(
            status = TaskStatus.Done,
            sortOrder = 0,
            completedAt = now,
            pendingOrderNeedsCompaction = true,
        )
        current == TaskStatus.Done && requested == TaskStatus.Pending -> TaskStatusTransition(
            status = TaskStatus.Pending,
            sortOrder = appendedPendingSortOrder,
            completedAt = null,
            pendingOrderNeedsCompaction = false,
        )
        else -> TaskStatusTransition(
            status = TaskStatus.Done,
            sortOrder = 0,
            completedAt = currentCompletedAt ?: now,
            pendingOrderNeedsCompaction = false,
        )
    }
}

fun normalizeProjectName(name: String): String = name.trim().lowercase(Locale.ROOT)

fun requireProjectName(name: String): String {
    val trimmed = name.trim()
    require(trimmed.isNotEmpty()) { "Project name is required" }
    return trimmed
}

fun requireTaskTitle(title: String): String {
    val trimmed = title.trim()
    require(trimmed.isNotEmpty()) { "Task title is required" }
    return trimmed
}
