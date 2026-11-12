/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.byteflipper.everbook.domain.use_case.book

import android.net.Uri
import com.byteflipper.everbook.domain.library.book.Book
import com.byteflipper.everbook.domain.reader.ReaderText
import com.byteflipper.everbook.domain.repository.BookExportRepository
import javax.inject.Inject

class ExportEditedBook @Inject constructor(
    private val repository: BookExportRepository
) {
    suspend operator fun invoke(
        book: Book,
        editedText: List<ReaderText>,
        destination: Uri
    ) = repository.exportEditedCopy(book, editedText, destination)
}
