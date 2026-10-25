package com.couchtasks.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClockTest {
    @Test
    fun utcNowRfc3339_usesWholeSecondsWithLiteralZ() {
        val now = utcNowRfc3339()

        assertTrue(now, Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""").matches(now))
    }

    @Test
    fun utcRfc3339DaysAgo_subtractsWholeDaysAndDropsFraction() {
        val now = Instant.parse("2026-10-25T12:34:56.789Z")

        assertEquals("2026-10-18T12:34:56Z", utcRfc3339DaysAgo(7, now))
    }
}
