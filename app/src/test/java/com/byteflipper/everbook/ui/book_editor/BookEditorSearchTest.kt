/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.ui.book_editor

import androidx.compose.ui.text.AnnotatedString
import com.byteflipper.everbook.domain.reader.ReaderText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookEditorSearchTest {
    @Test
    fun alefNormalizationReturnsExactOriginalRange() {
        val source = "ألف ألف"
        val entry = ReaderText.Text(AnnotatedString(source), source)

        val result = BookEditorSearch.find(
            entries = listOf(entry),
            query = "ا",
            options = BookEditorSearchOptions(
                normalization = ArabicSearchNormalization.ALEF
            )
        ) as BookEditorSearchResult.Matches

        assertEquals(2, result.items.size)
        assertEquals(0, result.items.first().start)
        assertEquals(1, result.items.first().end)
        assertEquals("أ", result.items.first().matchedText)
    }

    @Test
    fun wholeWordDoesNotMatchInsideAnotherWord() {
        val source = "one stone"
        val entry = ReaderText.Text(AnnotatedString(source), source)

        val result = BookEditorSearch.find(
            entries = listOf(entry),
            query = "one",
            options = BookEditorSearchOptions(wholeWord = true)
        ) as BookEditorSearchResult.Matches

        assertEquals(1, result.items.size)
        assertEquals(0, result.items.single().start)
    }

    @Test
    fun regexReplacementChangesOnlySelectedMatch() {
        val source = "one one"
        val entry = ReaderText.Text(AnnotatedString(source), source)
        val options = BookEditorSearchOptions(regex = true)
        val matches = BookEditorSearch.find(listOf(entry), "one", options)
            as BookEditorSearchResult.Matches

        val replaced = BookEditorSearch.replace(
            source = source,
            matches = matches.items,
            selectedKeys = setOf(matches.items.last().key),
            replacement = "two",
            options = options,
            query = "one"
        )

        assertEquals("one two", replaced)
    }

    @Test
    fun malformedRegexIsReportedInsteadOfThrowing() {
        val entry = ReaderText.Text(AnnotatedString("text"), "text")
        val result = BookEditorSearch.find(
            entries = listOf(entry),
            query = "[",
            options = BookEditorSearchOptions(regex = true)
        )

        assertTrue(result is BookEditorSearchResult.InvalidPattern)
    }
}
