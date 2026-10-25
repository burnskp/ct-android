package com.couchtasks.android.viewmodel

import com.couchtasks.android.data.ReviewProject
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewQueueTest {
    private val inbox = ReviewProject("inbox", "Inbox", isInbox = true)
    private val home = ReviewProject("home", "Home", isInbox = false)
    private val work = ReviewProject("work", "Work", isInbox = false)
    private val queue = listOf(inbox, home, work)

    @Test
    fun noSkips_keepsQueueOrder() {
        assertEquals(queue, orderReviewQueue(queue, emptyList()))
    }

    @Test
    fun skipped_movesToBackInSkipOrder() {
        assertEquals(listOf(work, home, inbox), orderReviewQueue(queue, listOf("home", "inbox")))
    }

    @Test
    fun everyProjectSkipped_cyclesBackToFirstSkipped() {
        assertEquals(listOf(inbox, home, work), orderReviewQueue(queue, listOf("inbox", "home", "work")))
    }

    @Test
    fun skippedProjectsNoLongerQueued_areIgnored() {
        assertEquals(listOf(inbox, work), orderReviewQueue(listOf(inbox, work), listOf("home")))
    }
}
