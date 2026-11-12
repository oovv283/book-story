/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.domain.reader

import com.byteflipper.everbook.domain.reader.ReaderText.Chapter
import java.util.UUID

/** The source text remains intact; display filtering hides only non-linear spine sections. */
internal data class ReaderTextFlow(
    val sourceText: List<ReaderText>,
    val chapters: List<Chapter>,
    val chapterIndexes: List<Int>,
    val navigationChapters: List<Chapter>,
    val navigationOnlyText: Map<UUID, List<ReaderText>>,
    val hiddenTextIndexes: Set<Int>,
    val defaultStartIndex: Int?
)

/**
 * Keeps the parser's original item order and indexes intact, while marking non-linear sections
 * for display-only filtering and retaining each section for direct navigation from the TOC.
 */
internal fun partitionReaderTextFlow(entries: List<ReaderText>): ReaderTextFlow {
    val chapters = mutableListOf<Chapter>()
    val chapterIndexes = mutableListOf<Int>()
    val navigationChapters = mutableListOf<Chapter>()
    val navigationOnlyText = linkedMapOf<UUID, MutableList<ReaderText>>()
    val hiddenTextIndexes = linkedSetOf<Int>()
    var activeNavigationOnlyChapterId: UUID? = null

    entries.forEachIndexed { sourceIndex, entry ->
        if (entry is Chapter) {
            navigationChapters += entry
            if (entry.isLinear) {
                activeNavigationOnlyChapterId = null
                chapters += entry
                chapterIndexes += sourceIndex
            } else {
                activeNavigationOnlyChapterId = entry.id
                navigationOnlyText[entry.id] = mutableListOf(entry)
                hiddenTextIndexes += sourceIndex
            }
        } else {
            val activeId = activeNavigationOnlyChapterId
            if (activeId != null) {
                navigationOnlyText[activeId]?.add(entry)
                hiddenTextIndexes += sourceIndex
            }
        }
    }

    val firstReadingChapterPosition = chapters.indexOfFirst { chapter ->
        !chapter.isFrontMatterLabel()
    }.takeIf { it >= 0 } ?: 0

    return ReaderTextFlow(
        sourceText = entries.toList(),
        chapters = chapters,
        chapterIndexes = chapterIndexes,
        navigationChapters = navigationChapters,
        navigationOnlyText = navigationOnlyText.mapValues { (_, text) -> text.toList() },
        hiddenTextIndexes = hiddenTextIndexes,
        // Keep front matter reachable by scrolling, but start a new book at its first real chapter.
        defaultStartIndex = chapterIndexes.getOrNull(firstReadingChapterPosition)
    )
}

private fun Chapter.isFrontMatterLabel(): Boolean {
    val normalizedTitle = title
        .lowercase()
        .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    val frontMatterLabels = listOf(
        "الغلاف",
        "صفحة الغلاف",
        "صفحة العنوان",
        "عنوان الكتاب",
        "حقوق النشر",
        "حقوق الطبع والنشر",
        "صفحة الحقوق",
        "معلومات النشر",
        "بيانات النشر",
        "فهرس المحتويات",
        "المحتويات",
        "الفهرس",
        "الإهداء",
        "إهداء",
        "cover",
        "front cover",
        "title page",
        "half title",
        "copyright",
        "rights page",
        "publication details",
        "imprint",
        "contents",
        "table of contents",
        "dedication"
    )
    return frontMatterLabels.any { label ->
        normalizedTitle == label || normalizedTitle.startsWith("$label ")
    }
}
