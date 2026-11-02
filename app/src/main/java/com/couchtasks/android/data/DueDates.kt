package com.couchtasks.android.data

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Due-date grammar, storage form, and display, matching the `ct` CLI (`src/due.rs`). */
object DueDates {
    /** The time a due date gets when the input has no time token. */
    val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)

    private const val MINUTES_PER_HOUR = 60L
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
    private const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY
    private const val MAX_RELATIVE_COUNT = 4_294_967_295L // u32::MAX in the CLI

    private val RELATIVE = Regex("""(?:(\d+)w)?(?:(\d+)d)?""", RegexOption.IGNORE_CASE)
    private val DIGITS = Regex("""\d+""")
    private val TIME = Regex("""(\d{1,2}):(\d{2})""")
    private val CANONICAL = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")
    private val ABSOLUTE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")

    /** True for `YYYY-MM-DDTHH:MM:SSZ`: UTC, whole seconds, literal `Z`. */
    fun isCanonical(value: String): Boolean {
        if (!CANONICAL.matches(value)) return false
        val instant = try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            return false
        }
        return toCanonical(instant) == value
    }

    fun requireCanonical(value: String): String {
        require(isCanonical(value)) { "Invalid due_at timestamp: \"$value\"" }
        return value
    }

    fun toCanonical(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

    /**
     * Parses CLI due-date input (`YYYY-MM-DD`, `YY-MM-DD`, `MM-DD`, `DD`, or `<n>w<n>d`, optionally followed by `H:MM`)
     * in [zone], relative to [now], into canonical UTC. Throws [IllegalArgumentException] with a user-facing message.
     */
    fun parse(input: String, now: Instant, zone: ZoneId): String {
        val tokens = input.trim().split(Regex("""\s+""")).filter(String::isNotEmpty)
        if (tokens.isEmpty() || tokens.size > 2) invalid(input)
        val today = now.atZone(zone).toLocalDate()
        val date = parseRelative(tokens[0], today) ?: parseCalendar(tokens[0], today) ?: invalid(input)
        val time = if (tokens.size == 2) parseTime(tokens[1]) ?: invalid(input) else DEFAULT_TIME
        return toUtc(LocalDateTime.of(date, time), zone)
    }

    /** Converts a local date-time to canonical UTC. Gaps are errors; overlaps take the earlier instant. */
    fun toUtc(local: LocalDateTime, zone: ZoneId): String {
        require(zone.rules.getValidOffsets(local).isNotEmpty()) {
            "Due time does not exist in local time: ${local.format(ABSOLUTE_FORMAT)}"
        }
        return toCanonical(ZonedDateTime.ofLocal(local, zone, null).toInstant())
    }

    /** Local `YYYY-MM-DD HH:MM`. Non-canonical stored values are shown as they are. */
    fun formatAbsolute(dueAt: String, zone: ZoneId): String {
        if (!isCanonical(dueAt)) return dueAt
        return Instant.parse(dueAt).atZone(zone).format(ABSOLUTE_FORMAT)
    }

    /**
     * Time between [now] and the due time, truncated to whole minutes, as `2w3d`, `4d`, `2d5h`, or `5h30m`. The value is a
     * magnitude: overdue tasks are marked by [isOverdue], not a sign. Non-canonical stored values are shown as they are.
     */
    fun formatRelative(dueAt: String, now: Instant): String {
        if (!isCanonical(dueAt)) return dueAt
        val minutes = Math.abs(Duration.between(now, Instant.parse(dueAt)).toMinutes())
        val parts = when {
            minutes >= MINUTES_PER_WEEK -> listOf(
                minutes / MINUTES_PER_WEEK to "w",
                minutes % MINUTES_PER_WEEK / MINUTES_PER_DAY to "d",
            )
            minutes >= 3 * MINUTES_PER_DAY -> listOf(minutes / MINUTES_PER_DAY to "d")
            minutes >= MINUTES_PER_DAY -> listOf(
                minutes / MINUTES_PER_DAY to "d",
                minutes % MINUTES_PER_DAY / MINUTES_PER_HOUR to "h",
            )
            else -> listOf(minutes / MINUTES_PER_HOUR to "h", minutes % MINUTES_PER_HOUR to "m")
        }
        return parts.filter { it.first != 0L }.joinToString("") { "${it.first}${it.second}" }.ifEmpty { "0m" }
    }

    /** A pending task is overdue when its due time is strictly before [now]; done tasks never are. */
    fun isOverdue(dueAt: String?, status: String, now: Instant): Boolean =
        status == TaskStatus.Pending && dueAt != null && isCanonical(dueAt) && Instant.parse(dueAt).isBefore(now)

    private fun parseRelative(token: String, today: LocalDate): LocalDate? {
        val match = RELATIVE.matchEntire(token) ?: return null
        val (weeksText, daysText) = match.destructured
        if (weeksText.isEmpty() && daysText.isEmpty()) return null
        val weeks = relativeCount(weeksText) ?: return null
        val days = relativeCount(daysText) ?: return null
        return try {
            today.plusDays(Math.addExact(Math.multiplyExact(weeks, 7L), days))
        } catch (_: ArithmeticException) {
            null
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun relativeCount(text: String): Long? {
        if (text.isEmpty()) return 0
        return text.toLongOrNull()?.takeIf { it <= MAX_RELATIVE_COUNT }
    }

    private fun parseCalendar(token: String, today: LocalDate): LocalDate? {
        val parts = token.split('-')
        if (parts.any { !DIGITS.matches(it) }) return null
        val (year, month, day) = when (parts.size) {
            3 -> {
                val year = when (parts[0].length) {
                    4 -> parts[0].toInt()
                    2 -> 2000 + parts[0].toInt()
                    else -> return null
                }
                Triple(year, monthOrDay(parts[1]) ?: return null, monthOrDay(parts[2]) ?: return null)
            }
            2 -> Triple(today.year, monthOrDay(parts[0]) ?: return null, monthOrDay(parts[1]) ?: return null)
            1 -> Triple(today.year, today.monthValue, monthOrDay(parts[0]) ?: return null)
            else -> return null
        }
        return try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun monthOrDay(text: String): Int? = text.takeIf { it.length <= 2 }?.toInt()

    private fun parseTime(token: String): LocalTime? {
        val match = TIME.matchEntire(token) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null
        return LocalTime.of(hour, minute)
    }

    private fun invalid(input: String): Nothing = throw IllegalArgumentException(
        "Invalid due date: $input (expected YYYY-MM-DD, YY-MM-DD, MM-DD, DD, or <n>w<n>d, optionally followed by HH:MM)",
    )
}

/** Due-field rules for every write: canonical `dueAt`, and no reminder without a due date. */
fun normalizeDue(dueAt: String?, remind: Boolean): Pair<String?, Boolean> {
    dueAt?.let(DueDates::requireCanonical)
    return dueAt to (remind && dueAt != null)
}
