/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.data.parser.epub

import com.byteflipper.everbook.domain.reader.ReaderText
import com.byteflipper.everbook.presentation.core.util.clearAllMarkdown

/** Identifies a spine item whose text is the publisher description in EPUB metadata. */
internal object EpubDescriptionFilter {
    fun isDescriptionSection(
        readerText: List<ReaderText>,
        packageDescription: String,
        sectionTitles: List<String>,
        isEarlySpineItem: Boolean
    ): Boolean {
        val description = normalize(packageDescription.clearAllMarkdown())
        if (description.isBlank()) return false

        val paragraphs = readerText
            .filterIsInstance<ReaderText.Text>()
            .map { normalize(it.source.clearAllMarkdown()) }
            .filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return false

        paragraphs.indices.forEach { start ->
            var joined = ""
            for (index in start until paragraphs.size) {
                joined = if (joined.isEmpty()) paragraphs[index] else "$joined ${paragraphs[index]}"
                if (joined == description) return true
                if (!description.startsWith(joined)) break
            }
        }

        if (!isEarlySpineItem) return false
        return (sectionTitles + paragraphs.first()).any(::isSynopsisHeading)
    }

    private fun isSynopsisHeading(value: String): Boolean {
        val normalized = normalize(value)
            .lowercase()
            .replace(ARABIC_DIACRITICS, "")
        return normalized.startsWith("نبذة عن") ||
            normalized.startsWith("نبذة الكتاب") ||
            normalized.startsWith("ملخص الرواية") ||
            normalized.startsWith("ملخص الكتاب") ||
            normalized == "وصف الكتاب" ||
            normalized == "description" ||
            normalized == "synopsis" ||
            normalized == "blurb" ||
            normalized == "about the book" ||
            normalized == "about this book" ||
            normalized == "book summary"
    }

    private fun normalize(value: String): String = value
        .replace(WHITESPACE, " ")
        .trim()

    private val WHITESPACE = Regex("\\s+")
    private val ARABIC_DIACRITICS = Regex("[\\u064B-\\u065F\\u0670]")
}
