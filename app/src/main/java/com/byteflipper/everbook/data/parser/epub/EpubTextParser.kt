/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.byteflipper.everbook.data.parser.epub

import android.util.Log
import androidx.core.net.toUri
import com.byteflipper.everbook.data.parser.DocumentParser
import com.byteflipper.everbook.data.parser.ReaderTextChunkBuffer
import com.byteflipper.everbook.data.parser.ReaderTextChunkSink
import com.byteflipper.everbook.data.parser.TextParser
import com.byteflipper.everbook.domain.file.CachedFile
import com.byteflipper.everbook.domain.reader.ReaderText
import com.byteflipper.everbook.domain.reader.hasReadableReaderText
import com.byteflipper.everbook.presentation.core.constants.provideImageExtensions
import com.byteflipper.everbook.presentation.core.util.containsVisibleText
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.jsoup.Jsoup

private const val EPUB_TAG = "EPUB Parser"
private typealias Source = String

private val EPUB_PARSER_DISPATCHER = Dispatchers.IO.limitedParallelism(3)

class EpubTextParser @Inject constructor(
    private val documentParser: DocumentParser
) : TextParser {

    override suspend fun parse(
        cachedFile: CachedFile,
        onChunk: ReaderTextChunkSink?
    ): List<ReaderText> {
        Log.i(EPUB_TAG, "Started EPUB parsing: ${cachedFile.name}.")

        return try {
            yield()

            val rawFile = cachedFile.rawFile
            if (rawFile == null || !rawFile.exists() || !rawFile.canRead()) return emptyList()

            val readerText = withContext(Dispatchers.IO) {
                ZipFile(rawFile).use { zip ->
                    val zipEntries = zip.entries().toList()
                    val tocEntry = zipEntries.find { entry ->
                        entry.name.endsWith(".ncx", ignoreCase = true)
                    }
                    val opfEntry = zipEntries.find { entry ->
                        entry.name.endsWith(".opf", ignoreCase = true)
                    }

                    val chapterEntries = zip.getChapterEntries(opfEntry, zipEntries)
                    val imageEntries = zipEntries.filter { entry ->
                        provideImageExtensions().any { format ->
                            entry.name.endsWith(format, ignoreCase = true)
                        }
                    }
                    val chapterTitleEntries = zip.getChapterTitleMapFromNavigation(
                        opfEntry = opfEntry,
                        zipEntries = zipEntries,
                        chapterEntries = chapterEntries
                    ) ?: zip.getChapterTitleMapFromToc(tocEntry)
                    val hasUsableToc = !chapterTitleEntries.isNullOrEmpty()

                    Log.i(EPUB_TAG, "TOC Entry: ${tocEntry?.name ?: "no toc.ncx"}")
                    Log.i(EPUB_TAG, "OPF Entry: ${opfEntry?.name ?: "no .opf entry"}")
                    Log.i(EPUB_TAG, "Chapter entries, size: ${chapterEntries.size}")
                    Log.i(EPUB_TAG, "Title entries, size: ${chapterTitleEntries?.size}")
                    Log.i(EPUB_TAG, "Using TOC as authoritative: $hasUsableToc")

                    zip.parseEpub(
                        chapterEntries = chapterEntries,
                        imageEntries = imageEntries,
                        chapterTitleEntries = chapterTitleEntries,
                        hasUsableToc = hasUsableToc,
                        onChunk = onChunk
                    )
                }
            }

            yield()

            if (!readerText.hasReadableReaderText()) {
                Log.e(EPUB_TAG, "Could not extract text from EPUB.")
                return emptyList()
            }

            Log.i(EPUB_TAG, "Successfully finished EPUB parsing.")
            readerText
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private suspend fun ZipFile.parseEpub(
        chapterEntries: List<ZipEntry>,
        imageEntries: List<ZipEntry>,
        chapterTitleEntries: Map<Source, ReaderText.Chapter>?,
        hasUsableToc: Boolean,
        onChunk: ReaderTextChunkSink?
    ): List<ReaderText> {
        val readerText = mutableListOf<ReaderText>()
        val imageEntriesByName = imageEntries.associateBy { image ->
            image.name.substringAfterLast('/').substringAfterLast(File.separator).lowercase()
        }

        val parsedChapters = ConcurrentLinkedQueue<Pair<Int, List<ReaderText>>>()
        coroutineScope {
            val jobs = chapterEntries.mapIndexed { index, entry ->
                async(EPUB_PARSER_DISPATCHER) {
                    yield()
                    parseZipEntry(
                        zip = this@parseEpub,
                        entry = entry,
                        imageEntriesByName = imageEntriesByName,
                        chapterTitleMap = chapterTitleEntries,
                        hasUsableToc = hasUsableToc
                    )?.let { parsedChapters.add(index to it) }
                    yield()
                }
            }
            jobs.awaitAll()
        }

        val chunkBuffer = ReaderTextChunkBuffer(onChunk = onChunk)
        parsedChapters.toList()
            .sortedBy { (index, _) -> index }
            .forEach { (_, chapter) ->
                readerText.addAll(chapter)
                chunkBuffer.addAll(chapter)
                yield()
            }

        chunkBuffer.flush()
        return readerText
    }

    private suspend fun ZipFile.parseZipEntry(
        zip: ZipFile,
        entry: ZipEntry,
        imageEntriesByName: Map<String, ZipEntry>,
        chapterTitleMap: Map<Source, ReaderText.Chapter>?,
        hasUsableToc: Boolean
    ): List<ReaderText>? {
        val content = withContext(Dispatchers.IO) {
            zip.getInputStream(entry)
        }.bufferedReader().use { it.readText() }

        var readerText = documentParser.parseDocument(
            document = Jsoup.parse(content),
            zipFile = zip,
            imageEntriesByName = imageEntriesByName,
            includeChapter = false
        ).toMutableList()

        val tocChapter = getChapterTitleFromToc(
            chapterSource = entry.name,
            chapterTitleMap = chapterTitleMap
        )
        if (tocChapter == null && hasUsableToc) {
            if (!readerText.hasReadableReaderText(requireChapter = false)) return null
            return readerText
        }

        val chapter = tocChapter ?: run {
            val firstVisibleText = readerText.firstOrNull { line ->
                line is ReaderText.Text && line.line.text.containsVisibleText()
            } as? ReaderText.Text ?: return null

            ReaderText.Chapter(
                title = firstVisibleText.line.text,
                nested = false
            )
        }

        readerText = readerText.dropWhile { line ->
            line is ReaderText.Text && line.line.text.lowercase() == chapter.title.lowercase()
        }.toMutableList()

        readerText.add(0, chapter)

        if (!readerText.hasReadableReaderText()) {
            Log.w(EPUB_TAG, "Could not extract text from [${entry.name}].")
            return null
        }

        return readerText
    }

    private suspend fun ZipFile.getChapterTitleMapFromNavigation(
        opfEntry: ZipEntry?,
        zipEntries: List<ZipEntry>,
        chapterEntries: List<ZipEntry>
    ): Map<Source, ReaderText.Chapter>? {
        if (opfEntry == null) return null

        return try {
            val opfContent = getInputStream(opfEntry).bufferedReader().use { it.readText() }
            val navHref = Jsoup.parse(opfContent)
                .select("manifest > item")
                .firstOrNull { item ->
                    item.attr("properties").split(Regex("\\s+")).any { it == "nav" }
                }
                ?.attr("href")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

            val navPath = EpubNavigationParser.resolveArchivePath(opfEntry.name, navHref)
                ?: return null
            val navEntry = zipEntries.firstOrNull { entry ->
                entry.name.replace('\\', '/') == navPath
            } ?: zipEntries.firstOrNull { entry ->
                entry.name.replace('\\', '/').equals(navPath, ignoreCase = true)
            } ?: return null

            val navContent = getInputStream(navEntry).bufferedReader().use { it.readText() }
            val spinePaths = chapterEntries.map { it.name.replace('\\', '/') }.toSet()
            val chapterTitles = linkedMapOf<Source, ReaderText.Chapter>()
            EpubNavigationParser.parse(navContent, navEntry.name).forEach { item ->
                if (item.sourcePath in spinePaths && item.sourcePath !in chapterTitles) {
                    chapterTitles[item.sourcePath] = ReaderText.Chapter(
                        title = item.title,
                        nested = item.nested
                    )
                }
            }

            chapterTitles.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(EPUB_TAG, "Could not parse EPUB 3 navigation.", e)
            null
        }
    }

    private suspend fun ZipFile.getChapterTitleMapFromToc(
        tocEntry: ZipEntry?
    ): Map<Source, ReaderText.Chapter>? {
        val tocContent = tocEntry?.let {
            withContext(Dispatchers.IO) {
                getInputStream(it)
            }.bufferedReader().use { reader -> reader.readText() }
        }
        val tocDocument = tocContent?.let { Jsoup.parse(it) }

        if (tocDocument == null) return null
        val titleMap = mutableMapOf<Source, ReaderText.Chapter>()

        tocDocument.select("navPoint").forEach { navPoint ->
            val title = navPoint.selectFirst("navLabel > text")?.text()
                .let { value ->
                    if (value.isNullOrBlank()) return@forEach
                    value.trim()
                }

            val source = navPoint.selectFirst("content")?.attr("src")?.trim()
                .let { value ->
                    if (value.isNullOrBlank()) return@forEach
                    value.toUri().path ?: value
                }.substringAfterLast(File.separator)

            val parent = navPoint.parent()
                .let { value ->
                    if (value == null) return@let null
                    if (!value.tagName().equals("navPoint", ignoreCase = true)) return@let null

                    val parentSource = value.selectFirst("content")?.attr("src")?.trim()
                        .let { parentValue ->
                            if (parentValue.isNullOrBlank()) return@forEach
                            parentValue.toUri().path ?: parentValue
                        }.substringAfterLast(File.separator)
                    if (parentSource == source) return@let null
                    return@let parentSource
                }

            val chapter = ReaderText.Chapter(
                title = titleMap[source]?.title.run {
                    if (this == null) return@run title
                    return@run "$this / $title"
                },
                nested = titleMap[source]?.nested ?: (parent != null)
            )
            titleMap[source] = chapter
        }

        return titleMap
    }

    private fun getChapterTitleFromToc(
        chapterSource: String,
        chapterTitleMap: Map<Source, ReaderText.Chapter>?
    ): ReaderText.Chapter? {
        if (chapterTitleMap.isNullOrEmpty()) return null
        val normalizedSource = chapterSource.replace('\\', '/').trimStart('/')
        return chapterTitleMap[normalizedSource]
            ?: chapterTitleMap.entries.firstOrNull {
                it.key.equals(normalizedSource, ignoreCase = true)
            }?.value
            ?: chapterTitleMap[normalizedSource.substringAfterLast('/')]
    }

    private fun ZipFile.getChapterEntries(
        opfEntry: ZipEntry?,
        zipEntries: List<ZipEntry>
    ): List<ZipEntry> {
        opfEntry?.let { entry ->
            val opfContent = getInputStream(entry).bufferedReader().use { reader ->
                reader.readText()
            }
            val document = Jsoup.parse(opfContent)

            val manifestItems = document.select("manifest > item").associate {
                it.attr("id") to it.attr("href")
            }

            document.select("spine > itemref").mapNotNull { itemRef ->
                val spineId = itemRef.attr("idref")
                val chapterSource = manifestItems[spineId]
                    ?.substringAfterLast(File.separator)
                    ?.lowercase()
                    ?: return@mapNotNull null

                zipEntries.find { zipEntry ->
                    zipEntry.name.substringAfterLast(File.separator).lowercase() == chapterSource
                }
            }.also { entries ->
                if (entries.isNotEmpty()) {
                    Log.i(EPUB_TAG, "Successfully parsed OPF to get entries from spine.")
                    return entries
                }
            }
        }

        Log.w(EPUB_TAG, "Could not parse OPF, manual filtering.")
        return zipEntries.filter { entry ->
            listOf(".html", ".htm", ".xhtml").any {
                entry.name.endsWith(it, ignoreCase = true)
            }
        }.sortedBy {
            it.name.filter { char -> char.isDigit() }.toBigIntegerOrNull()
        }
    }
}
