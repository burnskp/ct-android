package com.couchtasks.android.sync

import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex

object ConnectionOperations {
    val mutex = Mutex()
    @Volatile var stopping = false
    @Volatile var activeSync: Job? = null
}
