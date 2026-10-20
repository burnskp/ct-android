package com.couchtasks.android.data

import kotlinx.coroutines.sync.Mutex

object WriteSyncMutex {
    val mutex = Mutex()
}
