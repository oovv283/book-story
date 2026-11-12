/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.byteflipper.everbook.domain.repository

import android.net.Uri
import com.byteflipper.everbook.domain.library.book.Book
import com.byteflipper.everbook.domain.reader.ReaderText

interface BookExportRepository {
    /** Exports the supplied editor snapshot to a new user-selected document. */
    suspend fun exportEditedCopy(
        book: Book,
        editedText: List<ReaderText>,
        destination: Uri
    )
}
