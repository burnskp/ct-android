package com.couchtasks.android.viewmodel

internal fun Throwable.userVisibleMessage(): String = message?.takeIf { it.isNotBlank() } ?: "Something went wrong"
