package com.couchtasks.android.sync

import com.couchtasks.android.settings.SettingsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpportunisticSyncTriggerTest {
    @Test
    fun doesNotSyncWhenConfigurationIsMissing() = runTest {
        var syncCount = 0
        val trigger = OpportunisticSyncTrigger(
            scope = this,
            currentSettings = { SettingsState() },
            isClientCertificatePasswordAvailable = { false },
            syncNow = {
                syncCount++
                ConfiguredSyncResult.Failed
            },
            debounceMillis = 0,
        )

        trigger.triggerIfConfigured()
        advanceUntilIdle()

        assertEquals(0, syncCount)
    }

    @Test
    fun syncsWhenConfigurationIsReady() = runTest {
        var syncCount = 0
        val trigger = readyTrigger(
            syncNow = {
                syncCount++
                ConfiguredSyncResult.Failed
            },
            debounceMillis = 0,
        )

        trigger.triggerIfConfigured()
        advanceUntilIdle()

        assertEquals(1, syncCount)
    }

    @Test
    fun ignoresSyncFailureSoShareFlowIsNotRetriedOrCrashed() = runTest {
        val trigger = readyTrigger(
            syncNow = { error("network unavailable") },
            debounceMillis = 0,
        )

        trigger.triggerIfConfigured()
        advanceUntilIdle()
    }

    @Test
    fun rapidTriggersAreDebouncedIntoOneSync() = runTest {
        var syncCount = 0
        val trigger = readyTrigger(
            syncNow = {
                syncCount++
                ConfiguredSyncResult.Failed
            },
            debounceMillis = 1_000,
        )

        repeat(5) { trigger.triggerIfConfigured() }
        advanceTimeBy(999)
        assertEquals(0, syncCount)

        advanceUntilIdle()
        assertEquals(1, syncCount)
    }

    @Test
    fun triggerDuringActiveSyncSchedulesOneFollowUpSync() = runTest {
        var syncCount = 0
        val allowFirstSyncToFinish = CompletableDeferred<Unit>()
        val trigger = readyTrigger(
            syncNow = {
                syncCount++
                if (syncCount == 1) allowFirstSyncToFinish.await()
                ConfiguredSyncResult.Failed
            },
            debounceMillis = 0,
        )

        trigger.triggerIfConfigured()
        runCurrent()
        assertEquals(1, syncCount)

        repeat(3) { trigger.triggerIfConfigured() }
        runCurrent()
        assertEquals(1, syncCount)

        allowFirstSyncToFinish.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, syncCount)
    }

    private fun kotlinx.coroutines.test.TestScope.readyTrigger(
        syncNow: suspend () -> ConfiguredSyncResult,
        debounceMillis: Long,
    ): OpportunisticSyncTrigger = OpportunisticSyncTrigger(
        scope = this,
        currentSettings = {
            SettingsState(
                serverUrl = "https://tasks.example.com",
                clientCertificateUri = "content://certs/client.p12",
                caCertificateUri = "content://certs/ca.pem",
            )
        },
        isClientCertificatePasswordAvailable = { true },
        syncNow = syncNow,
        debounceMillis = debounceMillis,
    )
}
