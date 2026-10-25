package com.couchtasks.android.data

import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTest {
    @Test
    fun utcNowRfc3339_usesWholeSecondsWithLiteralZ() {
        val now = utcNowRfc3339()

        assertTrue(now, Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""").matches(now))
    }
}
