/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.data.editor

import android.app.Application
import androidx.annotation.Keep
import com.google.gson.Gson
import com.byteflipper.everbook.data.parser.MarkdownParser
import com.byteflipper.everbook.domain.file.CachedFile
import com.byteflipper.everbook.domain.reader.ReaderText
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores edited reader text separately from the imported/source file. This keeps EPUB packages,
 * HTML/XML structure, images, and the user's original file intact while making edits persistent
 * inside the app and visible in the normal reader.
 */
@Singleton
class BookTextEditStore @Inject constructor(
    private val application: Application,
    private val markdownParser: MarkdownParser
) {
    private val gson = Gson()

    suspend fun load(
        bookId: Int,
        source: CachedFile
    ): Map<Int, BookTextEditEntry>? = withContext(Dispatchers.IO) {
        val file = editFile(bookId)
        if (!file.isFile) return@withContext null

        runCatching {
            val stored = file.bufferedReader().use { reader ->
                gson.fromJson(reader, BookTextEditManifest::class.java)
            } ?: return@runCatching null

            if (stored.version != SCHEMA_VERSION ||
                stored.sourcePath != source.path ||
                stored.sourceSize != source.size ||
                stored.sourceLastModified != source.lastModified
            ) {
                return@runCatching null
            }
            stored.entries.associateBy { it.index }
        }.getOrNull()
    }

    fun apply(
        sourceText: List<ReaderText>,
        edits: Map<Int, BookTextEditEntry>?,
        startIndex: Int = 0
    ): List<ReaderText> {
        if (edits.isNullOrEmpty()) return sourceText

        return sourceText.mapIndexed { localIndex, entry ->
            val edit = edits[startIndex + localIndex] ?: return@mapIndexed entry
            when {
                entry is ReaderText.Chapter && edit.kind == KIND_CHAPTER ->
                    entry.copy(title = edit.value)

                entry is ReaderText.Text && edit.kind == KIND_TEXT ->
                    entry.copy(
                        line = markdownParser.parse(edit.value),
                        source = edit.value
                    )

                entry is ReaderText.Math && edit.kind == KIND_MATH ->
                    entry.copy(latex = edit.value)

                else -> entry
            }
        }
    }

    suspend fun save(
        bookId: Int,
        source: CachedFile,
        editedText: List<ReaderText>
    ) = withContext(Dispatchers.IO) {
        val entries = editedText.mapIndexedNotNull { index, entry ->
            when (entry) {
                is ReaderText.Chapter -> BookTextEditEntry(index, KIND_CHAPTER, entry.title)
                is ReaderText.Text -> BookTextEditEntry(index, KIND_TEXT, entry.source)
                is ReaderText.Math -> BookTextEditEntry(index, KIND_MATH, entry.latex)
                is ReaderText.Image, ReaderText.Separator -> null
            }
        }
        if (entries.isEmpty()) {
            editFile(bookId).delete()
            return@withContext
        }

        val destination = editFile(bookId)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        val manifest = BookTextEditManifest(
            version = SCHEMA_VERSION,
            sourcePath = source.path,
            sourceSize = source.size,
            sourceLastModified = source.lastModified,
            entries = entries
        )

        try {
            temporary.bufferedWriter().use { writer -> gson.toJson(manifest, writer) }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            temporary.delete()
        }
    }

    suspend fun delete(bookId: Int) = withContext(Dispatchers.IO) {
        editFile(bookId).delete()
        File(editFile(bookId).parentFile, "${editFile(bookId).name}.tmp").delete()
    }

    private fun editFile(bookId: Int): File =
        File(File(application.filesDir, EDITS_DIRECTORY), "book_$bookId.json")

    @Keep
    private data class BookTextEditManifest(
        val version: Int,
        val sourcePath: String,
        val sourceSize: Long,
        val sourceLastModified: Long,
        val entries: List<BookTextEditEntry>
    )

    companion object {
        private const val EDITS_DIRECTORY = "book_text_edits"
        private const val SCHEMA_VERSION = 1
        private const val KIND_CHAPTER = "chapter"
        private const val KIND_TEXT = "text"
        private const val KIND_MATH = "math"
    }
}

@Keep
data class BookTextEditEntry(
    val index: Int,
    val kind: String,
    val value: String
)
