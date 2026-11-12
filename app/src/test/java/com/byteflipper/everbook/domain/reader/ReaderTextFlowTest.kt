/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.domain.reader

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTextFlowTest {
    @Test
    fun nonLinearTocIsHiddenAndNewReadingStartsAfterFrontMatter() {
        val cover = ReaderText.Chapter(title = "الغلاف", nested = false)
        val titlePage = ReaderText.Chapter(title = "صفحة العنوان", nested = false)
        val rightsPage = ReaderText.Chapter(title = "حقوق النشر", nested = false)
        val printedToc = ReaderText.Chapter(
            title = "فهرس الطبعة الورقية",
            nested = false,
            isLinear = false
        )
        val printedTocLine = ReaderText.Text(AnnotatedString("الفصل الأول ........ ١٥"))
        val opening = ReaderText.Chapter(title = "البداية", nested = false)
        val openingText = ReaderText.Text(AnnotatedString("بداية الرواية"))
        val nextChapter = ReaderText.Chapter(title = "استهلال", nested = false)
        val source = listOf(
            cover,
            titlePage,
            rightsPage,
            printedToc,
            printedTocLine,
            opening,
            openingText,
            nextChapter
        )

        val flow = partitionReaderTextFlow(source)

        assertEquals(source, flow.sourceText)
        assertEquals(listOf(cover, titlePage, rightsPage, opening, nextChapter), flow.chapters)
        assertEquals(listOf(0, 1, 2, 5, 7), flow.chapterIndexes)
        assertEquals(source.filterIsInstance<ReaderText.Chapter>(), flow.navigationChapters)
        assertEquals(listOf(printedToc, printedTocLine), flow.navigationOnlyText[printedToc.id])
        assertEquals(setOf(3, 4), flow.hiddenTextIndexes)
        assertEquals(5, flow.defaultStartIndex)
    }

    @Test
    fun linearChapterAfterAlternateSectionEndsItsHiddenRange() {
        val alternate = ReaderText.Chapter(
            title = "Supplement",
            nested = false,
            isLinear = false
        )
        val alternateText = ReaderText.Text(AnnotatedString("reference only"))
        val chapter = ReaderText.Chapter(title = "Chapter 1", nested = false)
        val body = ReaderText.Text(AnnotatedString("body"))

        val flow = partitionReaderTextFlow(listOf(alternate, alternateText, chapter, body))

        assertEquals(setOf(0, 1), flow.hiddenTextIndexes)
        assertEquals(listOf(2), flow.chapterIndexes)
        assertEquals(2, flow.defaultStartIndex)
        assertTrue(flow.navigationOnlyText[alternate.id]!!.contains(alternateText))
    }
}
