/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.data.editor

import androidx.annotation.Keep
import com.google.gson.Gson
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Stable identity for the source file; history is not applied if the source changes. */
internal data class BookTextEditSourceIdentity(
    val path: String,
    val size: Long,
    val lastModified: Long
) {
    fun key(): String {
        val value = "$path\u0000$size\u0000$lastModified".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

@Keep
data class BookTextEditRevisionSummary(
    val id: String,
    val savedAt: Long,
    val isOriginal: Boolean,
    val entryCount: Int,
    val label: String? = null
)

/**
 * Stores compressed text snapshots separately from the active edit manifest. It keeps the
 * original-before-first-edit snapshot, the latest saved checkpoint for each local calendar day,
 * and a safety snapshot before restoration. Dated checkpoints are retained for one year; the
 * active manifest remains small and quick to load.
 */
internal class BookTextEditHistoryStore(
    private val rootDirectory: File,
    private val gson: Gson = Gson()
) {
    fun list(
        bookId: Int,
        source: BookTextEditSourceIdentity
    ): List<BookTextEditRevisionSummary> {
        val directory = directory(bookId, source)
        val index = readIndex(directory) ?: return emptyList()
        return index.revisions
            .filter { isValidId(it.id) && snapshotFile(directory, it.id).isFile }
            .sortedWith(
                    compareByDescending<BookTextEditRevisionSummary> { !it.isOriginal }
                        .thenByDescending { it.savedAt }
                        .thenByDescending { it.label == null }
            )
    }

    fun addRevision(
        bookId: Int,
        source: BookTextEditSourceIdentity,
        entries: List<BookTextEditEntry>,
        savedAt: Long,
        isOriginal: Boolean,
        label: String? = null
    ): List<BookTextEditRevisionSummary> {
        val directory = directory(bookId, source)
        val indexFile = File(directory, INDEX_FILE_NAME)
        val existing = readIndex(directory)?.revisions.orEmpty()

        if (isOriginal && existing.any { it.isOriginal }) {
            return list(bookId, source)
        }

        require(label == null || label == LABEL_BEFORE_RESTORE)
        val id = when {
            isOriginal -> ORIGINAL_ID
            label != null -> "restore_${savedAt}_${UUID.randomUUID().toString().take(8)}"
            else -> "day_${dayKey(savedAt)}"
        }
        val summary = BookTextEditRevisionSummary(
            id = id,
            savedAt = savedAt,
            isOriginal = isOriginal,
            entryCount = entries.size,
            label = label
        )
        val snapshot = BookTextEditRevisionSnapshot(
            version = SNAPSHOT_VERSION,
            source = source,
            summary = summary,
            entries = entries
        )
        writeAtomically(snapshotFile(directory, id)) { temporary ->
            GZIPOutputStream(FileOutputStream(temporary)).use { gzip ->
                OutputStreamWriter(gzip, Charsets.UTF_8).use { writer ->
                    gson.toJson(snapshot, writer)
                }
            }
        }

        val minimumSavedAt = System.currentTimeMillis() - HISTORY_RETENTION_MILLIS
        val retained = (existing.filter { it.id != id } + summary)
            .filter { it.isOriginal || it.savedAt >= minimumSavedAt }
            .sortedBy { it.savedAt }
        val retainedIds = retained.mapTo(mutableSetOf()) { it.id }
        existing.filter { it.id !in retainedIds }.forEach { old ->
            if (isValidId(old.id)) snapshotFile(directory, old.id).delete()
        }

        writeAtomically(indexFile) { temporary ->
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                gson.toJson(BookTextEditHistoryIndex(INDEX_VERSION, retained), writer)
            }
        }
        return list(bookId, source)
    }

    fun load(
        bookId: Int,
        source: BookTextEditSourceIdentity,
        revisionId: String
    ): List<BookTextEditEntry>? {
        if (!isValidId(revisionId)) return null
        val directory = directory(bookId, source)
        val indexed = readIndex(directory)?.revisions?.any { it.id == revisionId } == true
        if (!indexed) return null

        return runCatching {
            GZIPInputStream(FileInputStream(snapshotFile(directory, revisionId))).use { gzip ->
                InputStreamReader(gzip, Charsets.UTF_8).use { reader ->
                    val snapshot = gson.fromJson(reader, BookTextEditRevisionSnapshot::class.java)
                        ?: return@runCatching null
                    if (snapshot.version != SNAPSHOT_VERSION ||
                        snapshot.source != source || snapshot.summary.id != revisionId
                    ) {
                        return@runCatching null
                    }
                    snapshot.entries
                }
            }
        }.getOrNull()
    }

    fun delete(bookId: Int) {
        File(rootDirectory, "book_$bookId").deleteRecursively()
    }

    private fun directory(bookId: Int, source: BookTextEditSourceIdentity) =
        File(File(rootDirectory, "book_$bookId"), source.key())

    private fun snapshotFile(directory: File, id: String) =
        File(directory, "$id.json.gz")

    private fun readIndex(directory: File): BookTextEditHistoryIndex? {
        val file = File(directory, INDEX_FILE_NAME)
        if (!file.isFile) return null
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                gson.fromJson(reader, BookTextEditHistoryIndex::class.java)
            }
        }.getOrNull()?.takeIf { it.version == INDEX_VERSION }
    }

    private fun writeAtomically(destination: File, write: (File) -> Unit) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            write(temporary)
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

    private fun dayKey(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(timestamp))

    private fun isValidId(id: String): Boolean =
        id == ORIGINAL_ID || DAILY_ID_REGEX.matches(id) || RESTORE_ID_REGEX.matches(id)

    @Keep
    private data class BookTextEditHistoryIndex(
        val version: Int,
        val revisions: List<BookTextEditRevisionSummary>
    )

    @Keep
    private data class BookTextEditRevisionSnapshot(
        val version: Int,
        val source: BookTextEditSourceIdentity,
        val summary: BookTextEditRevisionSummary,
        val entries: List<BookTextEditEntry>
    )

    private companion object {
        const val INDEX_FILE_NAME = "index.json"
        const val INDEX_VERSION = 1
        const val SNAPSHOT_VERSION = 1
        const val ORIGINAL_ID = "original"
        const val LABEL_BEFORE_RESTORE = "before_restore"
        const val HISTORY_RETENTION_MILLIS = 365L * 24 * 60 * 60 * 1000
        val DAILY_ID_REGEX = Regex("day_\\d{4}-\\d{2}-\\d{2}")
        val RESTORE_ID_REGEX = Regex("restore_\\d{13}_[a-f0-9]{8}")
    }
}
