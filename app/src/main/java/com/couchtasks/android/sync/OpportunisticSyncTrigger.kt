package com.couchtasks.android.sync

import com.couchtasks.android.settings.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OpportunisticSyncTrigger(
    private val scope: CoroutineScope,
    private val currentSettings: suspend () -> SettingsState,
    private val isClientCertificatePasswordAvailable: suspend () -> Boolean,
    private val syncNow: suspend () -> ConfiguredSyncResult,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {
    private val lock = Any()
    private var worker: Job? = null
    private var rerunRequested = false

    fun triggerIfConfigured() {
        synchronized(lock) {
            rerunRequested = true
            if (worker?.isActive == true) return
            worker = scope.launch { runDebouncedSyncLoop() }
        }
    }

    private suspend fun runDebouncedSyncLoop() {
        while (true) {
            if (debounceMillis > 0) delay(debounceMillis)
            synchronized(lock) { rerunRequested = false }

            runCatching {
                val isReady = validateSyncPrerequisites(
                    currentSettings(),
                    isClientCertificatePasswordAvailable(),
                ) is SyncPrerequisiteResult.Ready
                if (isReady) syncNow()
            }

            val shouldRunAgain = synchronized(lock) {
                if (rerunRequested) {
                    true
                } else {
                    worker = null
                    false
                }
            }
            if (!shouldRunAgain) return
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 750L
    }
}
