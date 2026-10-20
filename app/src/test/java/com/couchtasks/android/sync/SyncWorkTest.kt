package com.couchtasks.android.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWorkTest {
    @Test
    fun transientFailuresRetryAtMostFiveTotalAttempts() {
        val transient = ConfiguredSyncResult.Http5xxFailure(503)
        assertTrue(shouldRetrySyncWork(transient, runAttemptCount = 0))
        assertTrue(shouldRetrySyncWork(transient, runAttemptCount = 3))
        assertFalse(shouldRetrySyncWork(transient, runAttemptCount = 4))
    }

    @Test
    fun interventionNeverRetries() {
        assertFalse(shouldRetrySyncWork(ConfiguredSyncResult.Http4xxMismatch(409), runAttemptCount = 0))
        assertFalse(shouldRetrySyncWork(ConfiguredSyncResult.TlsConfigurationFailure(Exception("TLS")), runAttemptCount = 0))
    }
}
