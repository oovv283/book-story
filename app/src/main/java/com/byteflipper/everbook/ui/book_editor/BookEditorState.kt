/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.ui.book_editor

import com.byteflipper.everbook.domain.library.book.Book
import com.byteflipper.everbook.domain.reader.ReaderText

internal data class BookEditorState(
    val book: Book? = null,
    val text: List<ReaderText> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
    val undoStack: List<List<ReaderText>> = emptyList(),
    val redoStack: List<List<ReaderText>> = emptyList(),
    val errorMessage: String? = null
)
