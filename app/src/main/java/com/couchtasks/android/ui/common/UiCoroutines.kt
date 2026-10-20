package com.couchtasks.android.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun CoroutineScope.launchCatching(block: suspend () -> Unit) {
    launch { runCatching { block() } }
}
