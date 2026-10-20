package com.couchtasks.android.share

import java.net.URI

data class SharePayload(
    val extraTitle: String = "",
    val extraText: String = "",
    val clipValues: List<String> = emptyList(),
)

data class ParsedShare(
    val title: String,
    val notes: String,
)

fun parseSharedContent(payload: SharePayload): ParsedShare? {
    val extraTitle = payload.extraTitle.trimForSharedInput()
    val extraText = payload.extraText.trimForSharedInput()
    val clipValues = payload.clipValues
        .take(MAX_CLIP_VALUES)
        .map { it.trimForSharedInput() }
        .filter { it.isNotBlank() }
    val firstUrl = (listOf(extraText) + clipValues).firstNotNullOfOrNull { extractFirstHttpUrl(it) }

    if (firstUrl != null) {
        val title = extraTitle.ifBlank { urlHostOrFallback(firstUrl) }
        return ParsedShare(title = title.trimForTaskTitle(), notes = firstUrl.trimForTaskNotes())
    }

    val firstText = (listOf(extraTitle, extraText) + clipValues)
        .firstOrNull { it.isNotBlank() }
        ?: return null
    val titleLine = firstText.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (titleLine.isBlank()) return null
    return ParsedShare(
        title = titleLine.trimForTaskTitle(),
        notes = if (firstText.trim() != titleLine) firstText.trimForTaskNotes() else "",
    )
}

fun extractFirstHttpUrl(text: String): String? = HTTP_URL_REGEX
    .find(text)
    ?.value
    ?.trimEnd('.', ',', ')', ']', '}', '>', '"', '\'')

private val HTTP_URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

private const val MAX_CLIP_VALUES = 8
private const val MAX_SHARED_INPUT_CHARS = 16_384
private const val MAX_TASK_TITLE_CHARS = 200
private const val MAX_TASK_NOTES_CHARS = 8_000

private fun urlHostOrFallback(url: String): String = runCatching {
    URI(url).host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url
}.getOrDefault(url)

private fun String.trimForSharedInput(): String = trim().take(MAX_SHARED_INPUT_CHARS)

private fun String.trimForTaskTitle(maxLength: Int = MAX_TASK_TITLE_CHARS): String =
    trim().replace(Regex("\\s+"), " ").take(maxLength)

private fun String.trimForTaskNotes(maxLength: Int = MAX_TASK_NOTES_CHARS): String = trim().take(maxLength)
