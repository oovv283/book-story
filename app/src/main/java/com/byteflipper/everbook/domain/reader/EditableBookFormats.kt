/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.domain.reader

private val editableBookExtensions = setOf("epub", "fb2", "txt", "html", "htm", "md")

fun String.isEditableBookFormat(): Boolean {
    val extension = substringAfterLast('.', missingDelimiterValue = "")
        .substringBefore('?')
        .lowercase()
    return extension in editableBookExtensions
}
