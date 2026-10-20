package com.couchtasks.android.data

fun compactOneBasedOrder(currentOrder: List<String>, preferredOrder: List<String> = emptyList()): List<Pair<String, Int>> {
    val current = currentOrder.distinct()
    val currentSet = current.toSet()
    val preferred = preferredOrder.filter { it in currentSet }.distinct()
    val ordered = preferred + current.filterNot { it in preferred.toSet() }
    return ordered.mapIndexed { index, uuid -> uuid to index + 1 }
}
