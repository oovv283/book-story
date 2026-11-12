/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.data.parser.epub

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal data class EpubNavigationItem(
    val sourcePath: String,
    val title: String,
    val nested: Boolean
)

/** Parses the EPUB 3 navigation document without inferring chapters from body prose. */
internal object EpubNavigationParser {
    private val whitespace = Regex("\\s+")
    private val uriScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun parse(navContent: String, navArchivePath: String): List<EpubNavigationItem> {
        val document = Jsoup.parse(navContent)
        val toc = document.select("nav").firstOrNull { nav ->
            nav.attr("epub:type").split(whitespace).any { it.equals("toc", ignoreCase = true) } ||
                nav.attr("type").equals("toc", ignoreCase = true)
        } ?: return emptyList()

        val entries = mutableListOf<EpubNavigationItem>()

        fun visitList(list: Element, depth: Int) {
            list.children()
                .filter { it.tagName().equals("li", ignoreCase = true) }
                .forEach { item ->
                    val anchor = item.children().firstOrNull {
                        it.tagName().equals("a", ignoreCase = true)
                    }
                    if (anchor != null) {
                        val title = anchor.text().replace(whitespace, " ").trim()
                        val sourcePath = resolveArchivePath(navArchivePath, anchor.attr("href"))
                        if (title.isNotBlank() && sourcePath != null) {
                            entries += EpubNavigationItem(
                                sourcePath = sourcePath,
                                title = title,
                                nested = depth > 0
                            )
                        }
                    }

                    item.children()
                        .filter { it.tagName().equals("ol", ignoreCase = true) }
                        .forEach { nestedList -> visitList(nestedList, depth + 1) }
                }
        }

        fun visitToTopLevelLists(element: Element) {
            if (element.tagName().equals("ol", ignoreCase = true)) {
                visitList(element, depth = 0)
                return
            }
            element.children().forEach(::visitToTopLevelLists)
        }

        visitToTopLevelLists(toc)
        return entries
    }

    /** Resolve a relative href against an archive file, dropping query and fragment targets. */
    fun resolveArchivePath(baseArchiveFile: String, href: String): String? {
        val rawPath = href.substringBefore('#').substringBefore('?').trim()
        if (rawPath.isBlank() || rawPath.startsWith("//") || uriScheme.containsMatchIn(rawPath)) {
            return null
        }

        val decodedPath = try {
            URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val joinedPath = if (decodedPath.startsWith('/')) {
            decodedPath
        } else {
            val directory = baseArchiveFile.replace('\\', '/').substringBeforeLast('/', "")
            if (directory.isBlank()) decodedPath else "$directory/$decodedPath"
        }

        val normalized = mutableListOf<String>()
        joinedPath.replace('\\', '/').split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.lastIndex) else return null
                else -> normalized += segment
            }
        }
        return normalized.joinToString("/").takeIf { it.isNotBlank() }
    }
}
