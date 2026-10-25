package com.couchtasks.android.share

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.couchtasks.android.CouchTasksApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ShareConfirmationInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database get() = (context.applicationContext as CouchTasksApplication).appContainer.database
    private fun count() = runBlocking { database.taskDao().allTasks().size }
    private fun intent() = Intent(context, ShareReceiverActivity::class.java).apply {
        action = Intent.ACTION_SEND
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Shared draft ${System.nanoTime()}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test fun backWithoutSavingDoesNotImportAndFinishes() {
        val before = count()
        ActivityScenario.launch<ShareReceiverActivity>(intent()).use { scenario ->
            compose.onNodeWithText("New Task").assertIsDisplayed()
            compose.onNodeWithText("‹").performClick()
            compose.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
        }
        assertEquals(before, count())
    }

    @Test fun savingImportsAndFinishes() {
        val before = count()
        ActivityScenario.launch<ShareReceiverActivity>(intent()).use { scenario ->
            compose.onNodeWithText("✓").performClick()
            compose.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
        }
        assertEquals(before + 1, count())
    }

    @Test fun unsupportedMimeDoesNotImport() {
        val before = count()
        ActivityScenario.launch<ShareReceiverActivity>(intent().apply { type = "image/png" }).use { }
        assertEquals(before, count())
    }
}
