package com.couchtasks.android.data

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** RFC 3339 UTC with whole seconds and a literal `Z`, matching the CLI; `reviewed_at` is compared as text. */
fun utcNowRfc3339(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS))

/** [utcNowRfc3339] shifted back [days], for comparing against `reviewed_at`. */
fun utcRfc3339DaysAgo(days: Long, now: Instant = Instant.now()): String =
    DateTimeFormatter.ISO_INSTANT.format(now.truncatedTo(ChronoUnit.SECONDS).minus(days, ChronoUnit.DAYS))
