/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.data.parser.epub

import androidx.compose.ui.text.AnnotatedString
import com.byteflipper.everbook.domain.reader.ReaderText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDescriptionFilterTest {
    @Test
    fun detectsDescriptionSplitAcrossParagraphsAndIgnoresItsHeading() {
        val section = listOf(
            ReaderText.Text(AnnotatedString("نبذة عن الرواية"), "نبذة عن الرواية"),
            ReaderText.Text(AnnotatedString("ملخص الرواية يبدأ هنا."), "ملخص الرواية يبدأ هنا."),
            ReaderText.Text(AnnotatedString("ويستمر في الفقرة التالية."), "ويستمر في الفقرة التالية.")
        )

        assertTrue(
            EpubDescriptionFilter.isDescriptionSection(
                section,
                "ملخص الرواية يبدأ هنا.\n\nويستمر في الفقرة التالية.",
                sectionTitles = listOf("نبذة عن الرواية"),
                isEarlySpineItem = true
            )
        )
    }

    @Test
    fun doesNotHideSectionWhenOnlyPartOfItMatchesMetadata() {
        val section = listOf(
            ReaderText.Text(AnnotatedString("البداية"), "البداية"),
            ReaderText.Text(AnnotatedString("ملخص الرواية يبدأ هنا، ثم يتابع."), "ملخص الرواية يبدأ هنا، ثم يتابع.")
        )

        assertFalse(
            EpubDescriptionFilter.isDescriptionSection(
                section,
                "ملخص الرواية يبدأ هنا",
                sectionTitles = listOf("البداية"),
                isEarlySpineItem = true
            )
        )
    }

    @Test
    fun doesNotHideOrdinaryChapterText() {
        val chapter = listOf(
            ReaderText.Chapter(title = "البداية", nested = false),
            ReaderText.Text(AnnotatedString("هذا نص روائي عادي."), "هذا نص روائي عادي.")
        )

        assertFalse(
            EpubDescriptionFilter.isDescriptionSection(
                chapter,
                "نبذة مختلفة عن الكتاب",
                sectionTitles = listOf("البداية"),
                isEarlySpineItem = false
            )
        )
    }

    @Test
    fun recognizesSynopsisHeadingWhenBodyIsLongerThanMetadataSummary() {
        val section = listOf(
            ReaderText.Text(AnnotatedString("نبذة عن الرواية"), "نبذة عن الرواية"),
            ReaderText.Text(AnnotatedString("وصف موسع للرواية لا يطابق ملخص البيانات حرفيًا."), "وصف موسع للرواية لا يطابق ملخص البيانات حرفيًا.")
        )

        assertTrue(
            EpubDescriptionFilter.isDescriptionSection(
                section,
                "ملخص أقصر داخل بيانات الكتاب.",
                sectionTitles = listOf("نبذة عن الرواية"),
                isEarlySpineItem = true
            )
        )
    }
}
