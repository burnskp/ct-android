package com.couchtasks.android.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareParserTest {
    @Test
    fun extractFirstHttpUrl_acceptsHttpAndHttpsAndTrimsPunctuation() {
        assertEquals("https://example.com/path", extractFirstHttpUrl("Read https://example.com/path)."))
        assertEquals("http://example.com", extractFirstHttpUrl("visit http://example.com"))
    }

    @Test
    fun parseSharedContent_usesExtraTitleAndUrlNotesForWebsiteShare() {
        assertEquals(
            ParsedShare(title = "Article title", notes = "https://example.com/story"),
            parseSharedContent(
                SharePayload(
                    extraTitle = " Article title ",
                    extraText = "https://example.com/story",
                ),
            ),
        )
    }

    @Test
    fun parseSharedContent_fallsBackToUrlHostThenFullUrl() {
        assertEquals(
            ParsedShare(title = "example.com", notes = "https://www.example.com/story"),
            parseSharedContent(SharePayload(extraText = "https://www.example.com/story")),
        )
        assertEquals(
            ParsedShare(title = "https://?x", notes = "https://?x"),
            parseSharedContent(SharePayload(extraText = "https://?x")),
        )
    }

    @Test
    fun parseSharedContent_usesClipDataUrl() {
        assertEquals(
            ParsedShare(title = "example.org", notes = "https://example.org/from-clip"),
            parseSharedContent(SharePayload(clipValues = listOf("https://example.org/from-clip"))),
        )
    }

    @Test
    fun parseSharedContent_usesFirstNonEmptyLineForGenericTextAndStoresFullTextInNotesWhenUseful() {
        assertEquals(
            ParsedShare(title = "First line", notes = "First line\nSecond line"),
            parseSharedContent(SharePayload(extraText = "\nFirst line\nSecond line")),
        )
        assertEquals(
            ParsedShare(title = "Only line", notes = ""),
            parseSharedContent(SharePayload(extraText = "Only line")),
        )
    }

    @Test
    fun parseSharedContent_truncatesOversizedNotesAndTitle() {
        val longTitle = "A".repeat(300)
        val titleOnly = parseSharedContent(SharePayload(extraText = longTitle))!!
        val withNotes = parseSharedContent(SharePayload(extraText = "Title\n${"B".repeat(20_000)}"))!!

        assertEquals(200, titleOnly.title.length)
        assertEquals(8_000, withNotes.notes.length)
        assertTrue(withNotes.notes.startsWith("Title\n"))
    }

    @Test
    fun parseSharedContent_ignoresClipValuesAfterLimit() {
        val clipValues = List(8) { "" } + listOf("https://example.com/ignored")

        assertNull(parseSharedContent(SharePayload(clipValues = clipValues)))
    }

    @Test
    fun parseSharedContent_returnsNullForBlankContent() {
        assertNull(parseSharedContent(SharePayload(extraTitle = " ", extraText = "\n")))
    }
}
