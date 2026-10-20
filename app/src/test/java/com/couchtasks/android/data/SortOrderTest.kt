package com.couchtasks.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SortOrderTest {
    @Test
    fun compactOneBasedOrder_rewritesToOneBasedSequence() {
        assertEquals(
            listOf("a" to 1, "b" to 2, "c" to 3),
            compactOneBasedOrder(listOf("a", "b", "c")),
        )
    }

    @Test
    fun compactOneBasedOrder_appliesPreferredOrderAndKeepsMissingAtEnd() {
        assertEquals(
            listOf("c" to 1, "a" to 2, "b" to 3),
            compactOneBasedOrder(
                currentOrder = listOf("a", "b", "c"),
                preferredOrder = listOf("c", "a"),
            ),
        )
    }

    @Test
    fun compactOneBasedOrder_ignoresUnknownAndDuplicateIds() {
        assertEquals(
            listOf("b" to 1, "a" to 2),
            compactOneBasedOrder(
                currentOrder = listOf("a", "b", "a"),
                preferredOrder = listOf("missing", "b", "b"),
            ),
        )
    }
}
