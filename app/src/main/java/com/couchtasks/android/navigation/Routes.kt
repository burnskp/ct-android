package com.couchtasks.android.navigation

object Routes {
    const val AllTasks = "all-tasks"
    const val Projects = "projects"
    const val Tasks = "tasks"
    const val Settings = "settings"
    const val SyncIssue = "sync-issue"
    const val ProjectTasks = "project/{projectUuid}"
    const val TaskEditor = "task/{projectUuid}/{taskUuid}"
    const val NewTaskUuid = "new"

    fun projectTasks(projectUuid: String) = "project/$projectUuid"

    fun taskEditor(projectUuid: String, taskUuid: String = NewTaskUuid) = "task/$projectUuid/$taskUuid"
}
