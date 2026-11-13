/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.presentation.reader

import androidx.compose.ui.text.AnnotatedString
import com.byteflipper.everbook.domain.reader.ReaderText
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderDisplayContentTest {
    @Test
    fun hidesNonLinearTextWhileKeepingOriginalReaderIndexes() {
        val cover = ReaderText.Text(AnnotatedString("cover"))
        val toc = ReaderText.Chapter(title = "Printed TOC", nested = false, isLinear = false)
        val tocLine = ReaderText.Text(AnnotatedString("Chapter 1 ... 12"))
        val chapter = ReaderText.Chapter(title = "The Beginning", nested = false)
        val body = ReaderText.Text(AnnotatedString("story"))
        val text = listOf(cover, toc, tocLine, chapter, body)

        val display = buildReaderDisplayContent(
            text = text,
            inlineContentPlacements = emptyList(),
            hiddenTextIndexes = setOf(1, 2)
        )

        assertEquals(listOf(cover, chapter, body), display.rows.map { (it as ReaderDisplayRow.Text).entry })
        assertEquals(3, display.displayIndexToTextIndex(1))
        assertEquals(4, display.displayIndexToTextIndex(2))
        assertEquals(1, display.textIndexToDisplayIndex(2))
        assertEquals(1, display.textIndexToDisplayIndex(3))
        assertEquals(2, display.textIndexToDisplayIndex(4))
    }

    @Test
    fun hidesDeletedParagraphWhileKeepingOriginalReaderIndexes() {
        val chapter = ReaderText.Chapter(title = "Chapter", nested = false)
        val before = ReaderText.Text(AnnotatedString("Before"))
        val deleted = ReaderText.Text(AnnotatedString(""), source = "", isDeleted = true)
        val after = ReaderText.Text(AnnotatedString("After"))

        val display = buildReaderDisplayContent(
            text = listOf(chapter, before, deleted, after),
            inlineContentPlacements = emptyList()
        )

        assertEquals(listOf(chapter, before, after), display.rows.map { (it as ReaderDisplayRow.Text).entry })
        assertEquals(3, display.displayIndexToTextIndex(2))
        assertEquals(2, display.textIndexToDisplayIndex(2))
        assertEquals(2, display.textIndexToDisplayIndex(3))
    }
}
