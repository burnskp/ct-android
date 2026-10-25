package com.couchtasks.android.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.couchtasks.android.CouchTasksApplication
import com.couchtasks.android.navigation.Routes
import com.couchtasks.android.ui.CouchTasksTheme
import com.couchtasks.android.ui.tasks.TaskEditorScreen
import com.couchtasks.android.viewmodel.TasksViewModel

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
        val repository = appContainer.tasksRepository
        setContent {
            CouchTasksTheme {
                val tasksViewModel: TasksViewModel = viewModel(
                    key = "share-tasks",
                    factory = viewModelFactory {
                        initializer { TasksViewModel(repository, appContainer.scheduleAutomaticSync) }
                    },
                )
                var inboxUuid by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(repository) {
                    try {
                        inboxUuid = repository.ensureInbox().uuid
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Toast.makeText(
                            this@ShareReceiverActivity,
                            failure.message ?: "Could not add to Couch Tasks",
                            Toast.LENGTH_LONG,
                        ).show()
                        finish()
                    }
                }
                LaunchedEffect(tasksViewModel) {
                    tasksViewModel.messages.collect {
                        Toast.makeText(this@ShareReceiverActivity, it, Toast.LENGTH_LONG).show()
                    }
                }
                inboxUuid?.let { inbox ->
                    TaskEditorScreen(
                        repository = repository,
                        tasksViewModel = tasksViewModel,
                        initialProjectUuid = inbox,
                        taskUuid = Routes.NewTaskUuid,
                        onBack = { finish() },
                        initialTitle = parsed.title,
                        initialNotes = parsed.notes,
                    )
                }
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
        private const val MAX_CLIP_VALUES = 8
        private const val MAX_SHARED_FIELD_CHARS = 16_384
    }
}
