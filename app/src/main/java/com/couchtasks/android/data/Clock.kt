package com.couchtasks.android.data

import java.time.Instant
import java.time.format.DateTimeFormatter

fun utcNowRfc3339(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
