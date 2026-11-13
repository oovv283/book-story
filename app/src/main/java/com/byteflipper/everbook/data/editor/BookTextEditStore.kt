/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.data.editor

import android.app.Application
import androidx.annotation.Keep
import androidx.compose.ui.text.AnnotatedString
import com.google.gson.Gson
import com.byteflipper.everbook.data.parser.MarkdownParser
import com.byteflipper.everbook.domain.file.CachedFile
import com.byteflipper.everbook.domain.reader.ReaderText
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val KIND_CHAPTER = "chapter"
private const val KIND_TEXT = "text"
private const val KIND_DELETED_TEXT = "deleted_text"
private const val KIND_MATH = "math"

internal fun applyBookTextEditEntries(
    sourceText: List<ReaderText>,
    edits: Map<Int, BookTextEditEntry>?,
    parseMarkdown: (String) -> AnnotatedString,
    startIndex: Int = 0
): List<ReaderText> {
    if (edits.isNullOrEmpty()) return sourceText

    return sourceText.mapIndexed { localIndex, entry ->
        val edit = edits[startIndex + localIndex] ?: return@mapIndexed entry
        when {
            entry is ReaderText.Chapter && edit.kind == KIND_CHAPTER ->
                entry.copy(title = edit.value)

            entry is ReaderText.Text && edit.kind == KIND_DELETED_TEXT ->
                entry.copy(line = parseMarkdown(""), source = "", isDeleted = true)

            entry is ReaderText.Text && edit.kind == KIND_TEXT ->
                entry.copy(line = parseMarkdown(edit.value), source = edit.value, isDeleted = false)

            entry is ReaderText.Math && edit.kind == KIND_MATH ->
                entry.copy(latex = edit.value)

            else -> entry
        }
    }
}

internal fun readerTextToBookTextEditEntries(text: List<ReaderText>): List<BookTextEditEntry> =
    text.mapIndexedNotNull { index, entry ->
        when (entry) {
            is ReaderText.Chapter -> BookTextEditEntry(index, KIND_CHAPTER, entry.title)
            is ReaderText.Text -> if (entry.isDeleted) {
                BookTextEditEntry(index, KIND_DELETED_TEXT, "")
            } else {
                BookTextEditEntry(index, KIND_TEXT, entry.source)
            }
            is ReaderText.Math -> BookTextEditEntry(index, KIND_MATH, entry.latex)
            is ReaderText.Image, ReaderText.Separator -> null
        }
    }

/**
 * Stores one persistent working copy of edited text separately from the imported/source file.
 * This store never writes to the source; format-specific exporters create and verify separate copies.
 */
@Singleton
class BookTextEditStore @Inject constructor(
    private val application: Application,
    private val markdownParser: MarkdownParser
) {
    private val gson = Gson()
    private val historyStore by lazy {
        BookTextEditHistoryStore(File(application.filesDir, HISTORY_DIRECTORY))
    }

    suspend fun load(
        bookId: Int,
        source: CachedFile
    ): Map<Int, BookTextEditEntry>? = withContext(Dispatchers.IO) {
        val manifest = readManifest(bookId, source) ?: return@withContext null
        manifest.entries.associateBy { it.index }
    }

    fun apply(
        sourceText: List<ReaderText>,
        edits: Map<Int, BookTextEditEntry>?,
        startIndex: Int = 0
    ): List<ReaderText> = applyBookTextEditEntries(
        sourceText = sourceText,
        edits = edits,
        parseMarkdown = markdownParser::parse,
        startIndex = startIndex
    )

    suspend fun save(
        bookId: Int,
        source: CachedFile,
        editedText: List<ReaderText>,
        previousText: List<ReaderText>?
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val old = readManifest(bookId, source)
        val entries = toEntries(editedText)
        val sourceIdentity = source.identity()
        val hasOriginalRevision = historyStore.list(bookId, sourceIdentity).any { it.isOriginal }
        val previousEntries = old?.entries?.takeIf { it.isNotEmpty() }
            ?: previousText?.let(::toEntries)

        if (!hasOriginalRevision && old?.entries.isNullOrEmpty() && previousEntries != null &&
            previousEntries != entries
        ) {
            historyStore.addRevision(
                bookId = bookId,
                source = sourceIdentity,
                entries = previousEntries,
                savedAt = now,
                isOriginal = true
            )
        }

        historyStore.addRevision(
            bookId = bookId,
            source = sourceIdentity,
            entries = entries,
            savedAt = now,
            isOriginal = false
        )

        writeManifest(
            bookId = bookId,
            manifest = BookTextEditManifest(
                version = SCHEMA_VERSION,
                sourcePath = source.path,
                sourceSize = source.size,
                sourceLastModified = source.lastModified,
                entries = entries,
                updatedAt = now,
                editedCopyUri = old?.editedCopyUri
            )
        )
    }

    suspend fun clearCurrent(bookId: Int, source: CachedFile) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val old = readManifest(bookId, source)
        if (!old?.entries.isNullOrEmpty()) {
            historyStore.addRevision(
                bookId = bookId,
                source = source.identity(),
                entries = old!!.entries,
                savedAt = old.updatedAt.takeIf { it > 0L } ?: now,
                isOriginal = false
            )
        }
        writeManifest(
            bookId = bookId,
            manifest = BookTextEditManifest(
                version = SCHEMA_VERSION,
                sourcePath = source.path,
                sourceSize = source.size,
                sourceLastModified = source.lastModified,
                entries = emptyList(),
                updatedAt = now,
                editedCopyUri = old?.editedCopyUri
            )
        )
    }

    suspend fun ensureOriginalRevision(
        bookId: Int,
        source: CachedFile,
        loadOriginalText: suspend () -> List<ReaderText>
    ) = withContext(Dispatchers.IO) {
        val sourceIdentity = source.identity()
        if (historyStore.list(bookId, sourceIdentity).any { it.isOriginal }) return@withContext
        val originalText = loadOriginalText()
        require(originalText.isNotEmpty()) { "Could not read the original text for edit history." }
        historyStore.addRevision(
            bookId = bookId,
            source = sourceIdentity,
            entries = toEntries(originalText),
            savedAt = System.currentTimeMillis(),
            isOriginal = true
        )
    }

    suspend fun history(
        bookId: Int,
        source: CachedFile
    ): List<BookTextEditRevisionSummary> = withContext(Dispatchers.IO) {
        if (readManifest(bookId, source) == null) return@withContext emptyList()
        historyStore.list(bookId, source.identity())
    }

    suspend fun preserveCurrentBeforeRestore(bookId: Int, source: CachedFile): Unit =
        withContext(Dispatchers.IO) {
            val current = readManifest(bookId, source) ?: return@withContext
            if (current.entries.isEmpty()) return@withContext
            historyStore.addRevision(
                bookId = bookId,
                source = source.identity(),
                entries = current.entries,
                savedAt = current.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                isOriginal = false,
                label = "before_restore"
            )
            Unit
        }

    suspend fun loadRevision(
        bookId: Int,
        source: CachedFile,
        revisionId: String
    ): List<BookTextEditEntry>? = withContext(Dispatchers.IO) {
        if (readManifest(bookId, source) == null) return@withContext null
        historyStore.load(bookId, source.identity(), revisionId)
    }

    suspend fun getEditedCopyUri(bookId: Int, source: CachedFile): String? =
        withContext(Dispatchers.IO) {
            readManifest(bookId, source)?.editedCopyUri
        }

    suspend fun setEditedCopyUri(
        bookId: Int,
        source: CachedFile,
        uri: String
    ) = withContext(Dispatchers.IO) {
        val old = readManifest(bookId, source)
        writeManifest(
            bookId = bookId,
            manifest = BookTextEditManifest(
                version = SCHEMA_VERSION,
                sourcePath = source.path,
                sourceSize = source.size,
                sourceLastModified = source.lastModified,
                entries = old?.entries.orEmpty(),
                updatedAt = old?.updatedAt?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                editedCopyUri = uri
            )
        )
    }

    suspend fun delete(bookId: Int) = withContext(Dispatchers.IO) {
        val file = editFile(bookId)
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
        historyStore.delete(bookId)
    }

    private fun readManifest(bookId: Int, source: CachedFile): BookTextEditManifest? {
        val file = editFile(bookId)
        if (!file.isFile) return null
        return runCatching {
            file.bufferedReader().use { reader ->
                gson.fromJson(reader, BookTextEditManifest::class.java)
            }
        }.getOrNull()?.takeIf {
            it.version == SCHEMA_VERSION &&
                it.sourcePath == source.path &&
                it.sourceSize == source.size &&
                it.sourceLastModified == source.lastModified
        }
    }

    private fun writeManifest(
        bookId: Int,
        manifest: BookTextEditManifest
    ) {
        val destination = editFile(bookId)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.${UUID.randomUUID()}.tmp")
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

    private fun toEntries(text: List<ReaderText>): List<BookTextEditEntry> =
        readerTextToBookTextEditEntries(text)

    private fun CachedFile.identity() = BookTextEditSourceIdentity(
        path = path,
        size = size,
        lastModified = lastModified
    )

    private fun editFile(bookId: Int): File =
        File(File(application.filesDir, EDITS_DIRECTORY), "book_$bookId.json")

    @Keep
    private data class BookTextEditManifest(
        val version: Int,
        val sourcePath: String,
        val sourceSize: Long,
        val sourceLastModified: Long,
        val entries: List<BookTextEditEntry>,
        val updatedAt: Long = 0L,
        val editedCopyUri: String? = null
    )

    companion object {
        private const val EDITS_DIRECTORY = "book_text_edits"
        private const val HISTORY_DIRECTORY = "book_text_edit_history"
        private const val SCHEMA_VERSION = 1
    }
}

@Keep
data class BookTextEditEntry(
    val index: Int,
    val kind: String,
    val value: String
)
