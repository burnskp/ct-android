package com.couchtasks.android.data

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** RFC 3339 UTC with whole seconds and a literal `Z`, matching the CLI; `reviewed_at` is compared as text. */
fun utcNowRfc3339(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS))
