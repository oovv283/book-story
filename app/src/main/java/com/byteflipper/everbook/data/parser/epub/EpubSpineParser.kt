/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.data.parser.epub

import org.jsoup.Jsoup

internal data class EpubSpineItem(
    val archivePath: String,
    val isLinear: Boolean
)

internal object EpubSpineParser {
    fun parse(opfContent: String, opfArchivePath: String): List<EpubSpineItem> {
        val document = Jsoup.parse(opfContent)
        val manifest = document.select("manifest > item").associate { item ->
            item.attr("id") to item.attr("href")
        }

        return document.select("spine > itemref").mapNotNull { itemRef ->
            val href = manifest[itemRef.attr("idref")]
                ?.substringBefore('#')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val archivePath = EpubNavigationParser.resolveArchivePath(opfArchivePath, href)
                ?: return@mapNotNull null

            EpubSpineItem(
                archivePath = archivePath,
                isLinear = !itemRef.attr("linear").equals("no", ignoreCase = true)
            )
        }
    }
}
