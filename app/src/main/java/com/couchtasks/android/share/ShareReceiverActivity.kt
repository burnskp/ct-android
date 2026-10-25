package com.couchtasks.android.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couchtasks.android.CouchTasksApplication
import com.couchtasks.android.MainActivity
import kotlinx.coroutines.launch

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val parsed = runCatching { intent.toSharePayload()?.let(::parseSharedContent) }.getOrNull()
        if (parsed == null) {
            Toast.makeText(this, "Nothing to add to Couch Tasks", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val appContainer = (application as CouchTasksApplication).appContainer
        setContent {
            com.couchtasks.android.ui.CouchTasksTheme {
                val switching by appContainer.database.connectionDao().observeJournal().collectAsStateWithLifecycle(null)
                var title by rememberSaveable { mutableStateOf(parsed.title) }
                var notes by rememberSaveable { mutableStateOf(parsed.notes) }
                val taskUuid = rememberSaveable { java.util.UUID.randomUUID().toString() }
                var submitting by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                AlertDialog(
                    onDismissRequest = { if (!submitting) finish() },
                    title = { Text("Add to Couch Tasks") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            OutlinedTextField(title, { title = it.take(200) }, label = { Text("Title") }, enabled = !submitting)
                            OutlinedTextField(notes, { notes = it.take(8000) }, label = { Text("Notes") }, enabled = !submitting, maxLines = 8)
                            if (switching != null) Text("Adding is temporarily unavailable during a server switch. Your draft is retained; resume or abandon the switch in Settings.")
                            error?.let { Text(it) }
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = !submitting && switching == null && title.isNotBlank(), onClick = {
                            submitting = true
                            lifecycleScope.launch {
                                try {
                                    val task = appContainer.tasksRepository.importSharedTask(taskUuid, title, notes)
                                    appContainer.scheduleAutomaticSync()
                                    startActivity(Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        putExtra(EXTRA_OPEN_TASK_UUID, task.uuid)
                                    })
                                    finish()
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Could not add to Couch Tasks"
                                } finally {
                                    submitting = false
                                }
                            }
                        }) { Text("Add") }
                    },
                    dismissButton = { TextButton(enabled = !submitting, onClick = { finish() }) { Text("Cancel") } },
                )
            }
        }
    }

    private fun Intent.toSharePayload(): SharePayload? {
        if (action != Intent.ACTION_SEND || type !in setOf("text/plain", "text/html")) return null
        return SharePayload(
            extraTitle = getCharSequenceExtra(Intent.EXTRA_TITLE)?.take(MAX_SHARED_FIELD_CHARS)?.toString().orEmpty().trimSharedField(),
            extraText = getCharSequenceExtra(Intent.EXTRA_TEXT)?.take(MAX_SHARED_FIELD_CHARS)?.toString().orEmpty().trimSharedField(),
            clipValues = buildList {
                val data = clipData ?: return@buildList
                for (index in 0 until minOf(data.itemCount, MAX_CLIP_VALUES)) {
                    val item = data.getItemAt(index)
                    item.text?.take(MAX_SHARED_FIELD_CHARS)?.toString()?.trimSharedField()?.takeIf { it.isNotBlank() }?.let(::add)
                    item.uri?.toString()?.trimSharedField()?.takeIf { it.isNotBlank() }?.let(::add)
                }
            },
        )
    }

    private fun String.trimSharedField(): String = trim().take(MAX_SHARED_FIELD_CHARS)

    companion object {
        const val EXTRA_OPEN_TASK_UUID = "open_task_uuid"
        private const val MAX_CLIP_VALUES = 8
        private const val MAX_SHARED_FIELD_CHARS = 16_384
    }
}
