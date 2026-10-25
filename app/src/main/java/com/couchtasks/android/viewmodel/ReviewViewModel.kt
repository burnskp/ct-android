package com.couchtasks.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couchtasks.android.data.ReviewProject
import com.couchtasks.android.data.TasksRepository
import com.couchtasks.android.data.utcRfc3339DaysAgo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours

/** Queue order with skipped projects moved to the back, most recently skipped last. */
fun orderReviewQueue(queue: List<ReviewProject>, skipped: List<String>): List<ReviewProject> =
    queue.sortedBy { skipped.indexOf(it.uuid) }

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModel(
    private val repository: TasksRepository,
    reviewIntervalDays: Flow<Int>,
    private val onLocalChange: () -> Unit,
) : ViewModel() {
    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val skipped = MutableStateFlow<List<String>>(emptyList())

    // Re-evaluates the cutoff hourly so tasks come due while the app stays open.
    private val hourly = flow {
        while (true) {
            emit(Unit)
            delay(1.hours)
        }
    }

    val queue: StateFlow<List<ReviewProject>> = combine(reviewIntervalDays, hourly) { days, _ -> days }
        .flatMapLatest { days -> repository.observeReviewQueue(utcRfc3339DaysAgo(days.toLong())) }
        .combine(skipped, ::orderReviewQueue)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun skip(projectUuid: String) {
        skipped.value = skipped.value - projectUuid + projectUuid
    }

    fun markReviewed(projectUuid: String) {
        viewModelScope.launch {
            runCatching { repository.markProjectReviewed(projectUuid) }
                .onSuccess {
                    skipped.value = skipped.value - projectUuid
                    onLocalChange()
                }
                .onFailure { _messages.emit(it.userVisibleMessage()) }
        }
    }
}
