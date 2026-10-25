package com.couchtasks.android.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.navigation.Routes
import com.couchtasks.android.ui.common.EmptyScreen
import com.couchtasks.android.ui.tasks.TaskRow
import com.couchtasks.android.viewmodel.ReviewViewModel
import com.couchtasks.android.viewmodel.TasksViewModel
import kotlinx.coroutines.flow.emptyFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    repository: TasksRepository,
    reviewViewModel: ReviewViewModel,
    tasksViewModel: TasksViewModel,
    navController: NavController,
) {
    val queue by reviewViewModel.queue.collectAsStateWithLifecycle()
    val current = queue.firstOrNull()
    val pendingFlow = remember(current?.uuid) {
        current?.let { repository.observePendingTasks(it.uuid) } ?: emptyFlow()
    }
    val pending by pendingFlow.collectAsStateWithLifecycle(emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = current?.name ?: "Review",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (current != null) {
                        if (queue.size > 1) {
                            TextButton(
                                onClick = { reviewViewModel.skip(current.uuid) },
                                modifier = Modifier.semantics { contentDescription = "Skip ${current.name}" },
                            ) { Text("»") }
                        }
                        // Inbox is reviewed by moving or completing its tasks, not by marking it.
                        if (!current.isInbox) {
                            TextButton(
                                onClick = { reviewViewModel.markReviewed(current.uuid) },
                                modifier = Modifier.semantics { contentDescription = "Mark ${current.name} reviewed" },
                            ) { Text("✓") }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (current == null) {
            EmptyScreen("Nothing to review")
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(pending, key = { task -> task.uuid }) { task ->
                    TaskRow(
                        task = task,
                        checked = false,
                        onClick = { navController.navigate(Routes.taskEditor(current.uuid, task.uuid)) },
                        onCheckedChange = { checked -> if (checked) tasksViewModel.completeTask(task.uuid) },
                    )
                }
            }
        }
    }
}
