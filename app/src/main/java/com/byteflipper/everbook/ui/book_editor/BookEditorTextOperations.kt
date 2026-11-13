/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.ui.book_editor

import com.byteflipper.everbook.domain.reader.ReaderText

internal fun List<ReaderText>.withEmptyParagraphDeleted(index: Int): List<ReaderText>? {
    val paragraph = getOrNull(index) as? ReaderText.Text ?: return null
    if (paragraph.isDeleted || paragraph.source.isNotBlank()) return null

    return toMutableList().also { entries ->
        entries[index] = paragraph.copy(isDeleted = true)
    }.toList()
}

internal fun BookEditorState.withEmptyParagraphDeletedAt(
    index: Int,
    savedText: List<ReaderText>
): BookEditorState? {
    val updated = text.withEmptyParagraphDeleted(index) ?: return null
    return copy(
        text = updated,
        isDirty = updated != savedText,
        undoStack = undoStack + listOf(text),
        redoStack = emptyList(),
        errorMessage = null
    )
}
