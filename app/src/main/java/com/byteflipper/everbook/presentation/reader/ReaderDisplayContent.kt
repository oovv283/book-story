/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.presentation.reader

import com.byteflipper.everbook.domain.distribution.ReaderInlineContentMode
import com.byteflipper.everbook.domain.distribution.ReaderInlineContentPlacement
import com.byteflipper.everbook.domain.reader.ReaderText

data class ReaderDisplayContent(
    val rows: List<ReaderDisplayRow>,
    private val displayToTextIndex: IntArray,
    private val textToDisplayIndex: IntArray
) {
    fun displayIndexToTextIndex(index: Int): Int {
        if (displayToTextIndex.isEmpty()) return 0
        return displayToTextIndex[index.coerceIn(displayToTextIndex.indices)]
    }

    fun textIndexToDisplayIndex(index: Int): Int {
        if (textToDisplayIndex.isEmpty()) return 0
        return textToDisplayIndex[index.coerceIn(textToDisplayIndex.indices)]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReaderDisplayContent

        if (rows != other.rows) return false
        if (!displayToTextIndex.contentEquals(other.displayToTextIndex)) return false
        if (!textToDisplayIndex.contentEquals(other.textToDisplayIndex)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = rows.hashCode()
        result = 31 * result + displayToTextIndex.contentHashCode()
        result = 31 * result + textToDisplayIndex.contentHashCode()
        return result
    }
}

sealed interface ReaderDisplayRow {
    data class Text(
        val readerIndex: Int,
        val entry: ReaderText
    ) : ReaderDisplayRow

    data class InlineContent(
        val placement: ReaderInlineContentPlacement
    ) : ReaderDisplayRow
}

fun buildReaderDisplayContent(
    text: List<ReaderText>,
    inlineContentPlacements: List<ReaderInlineContentPlacement>,
    hiddenTextIndexes: Set<Int> = emptySet()
): ReaderDisplayContent {
    val effectiveHiddenTextIndexes = buildSet {
        addAll(hiddenTextIndexes)
        text.forEachIndexed { index, entry ->
            if (entry is ReaderText.Text && entry.isDeleted) add(index)
        }
    }
    val placementsByTextIndex = inlineContentPlacements
        .asSequence()
        .filter { it.mode == ReaderInlineContentMode.TEXT }
        .filter { it.progressUnit in text.indices && it.progressUnit !in effectiveHiddenTextIndexes }
        .sortedBy { it.id }
        .groupBy { it.progressUnit }

    val rows = buildList {
        text.forEachIndexed { index, entry ->
            if (index !in effectiveHiddenTextIndexes) {
                add(ReaderDisplayRow.Text(readerIndex = index, entry = entry))
                placementsByTextIndex[index].orEmpty().forEach { placement ->
                    add(ReaderDisplayRow.InlineContent(placement = placement))
                }
            }
        }
    }
    val displayToTextIndex = IntArray(rows.size)
    val textToDisplayIndex = IntArray(text.size)
    val visibleTextIndexes = IntArray(text.size) { -1 }

    rows.forEachIndexed { displayIndex, row ->
        val textIndex = when (row) {
            is ReaderDisplayRow.Text -> {
                textToDisplayIndex[row.readerIndex] = displayIndex
                visibleTextIndexes[row.readerIndex] = displayIndex
                row.readerIndex
            }

            is ReaderDisplayRow.InlineContent -> row.placement.progressUnit
        }
        displayToTextIndex[displayIndex] = textIndex
    }

    if (text.isNotEmpty()) {
        var nextVisibleDisplayIndex = -1
        for (textIndex in text.indices.reversed()) {
            val visibleDisplayIndex = visibleTextIndexes[textIndex]
            if (visibleDisplayIndex >= 0) {
                nextVisibleDisplayIndex = visibleDisplayIndex
            } else {
                textToDisplayIndex[textIndex] = nextVisibleDisplayIndex
            }
        }

        var previousVisibleDisplayIndex = visibleTextIndexes.firstOrNull { it >= 0 } ?: 0
        for (textIndex in text.indices) {
            if (visibleTextIndexes[textIndex] >= 0) {
                previousVisibleDisplayIndex = visibleTextIndexes[textIndex]
            } else if (textToDisplayIndex[textIndex] < 0) {
                textToDisplayIndex[textIndex] = previousVisibleDisplayIndex
            }
        }
    }

    return ReaderDisplayContent(
        rows = rows,
        displayToTextIndex = displayToTextIndex,
        textToDisplayIndex = textToDisplayIndex
    )
}
