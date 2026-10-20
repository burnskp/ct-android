package com.couchtasks.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.couchtasks.android.CouchTasksApplication
import com.couchtasks.android.data.CouchTasksDatabase
import java.util.concurrent.TimeUnit

class SyncWorkScheduler(
    context: Context,
    private val db: CouchTasksDatabase,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    suspend fun enqueueAutomatic() {
        if (ConnectionOperations.stopping || db.connectionDao().journal() != null || db.syncIssueDao().get() != null) return
        enqueue(initialDelayMillis = DEBOUNCE_MILLIS, policy = ExistingWorkPolicy.REPLACE, allowExistingIssue = false)
    }

    fun enqueueTransientRetry() {
        enqueue(initialDelayMillis = 0, policy = ExistingWorkPolicy.KEEP, allowExistingIssue = true)
    }

    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private fun enqueue(initialDelayMillis: Long, policy: ExistingWorkPolicy, allowExistingIssue: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(ALLOW_EXISTING_ISSUE to allowExistingIssue))
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "couch-tasks-sync"
        const val DEBOUNCE_MILLIS = 750L
        const val MAX_ATTEMPTS = 5
        const val ALLOW_EXISTING_ISSUE = "allow_existing_issue"
        private const val MIN_BACKOFF_SECONDS = 10L
    }
}

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CouchTasksApplication).appContainer
        val allowExistingIssue = inputData.getBoolean(SyncWorkScheduler.ALLOW_EXISTING_ISSUE, false)
        if (!allowExistingIssue && container.database.syncIssueDao().get() != null) return Result.failure()
        val result = container.configuredSyncRunner.syncNow(scheduleBackgroundRetry = false)
        return when {
            result is ConfiguredSyncResult.Success -> Result.success()
            shouldRetrySyncWork(result, runAttemptCount) -> Result.retry()
            else -> Result.failure()
        }
    }
}

internal fun shouldRetrySyncWork(result: ConfiguredSyncResult, runAttemptCount: Int): Boolean =
    result.isTransientFailure() && runAttemptCount + 1 < SyncWorkScheduler.MAX_ATTEMPTS
