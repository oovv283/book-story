/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.byteflipper.everbook.data.repository

import android.app.Application
import android.net.Uri
import com.byteflipper.everbook.data.editor.TxtEditedCopySerializer
import com.byteflipper.everbook.domain.library.book.Book
import com.byteflipper.everbook.domain.reader.ReaderText
import com.byteflipper.everbook.domain.repository.BookExportRepository
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class BookExportRepositoryImpl @Inject constructor(
    private val application: Application
) : BookExportRepository {
    override suspend fun exportEditedCopy(
        book: Book,
        editedText: List<ReaderText>,
        destination: Uri
    ) = withContext(Dispatchers.IO) {
        val extension = book.filePath.substringAfterLast('.', "").lowercase()
        require(extension == "txt") {
            "A source-preserving exporter has not yet been verified for this book format."
        }
        require(destination.scheme == "content") {
            "Choose a writable document location."
        }

        val source = File(book.filePath).canonicalFile
        require(source.isFile && source.canRead()) { "The source book is no longer available." }
        require(source.length() <= MAX_EXPORT_BYTES) {
            "This TXT file is too large to export safely on this device."
        }
        val sourceBytes = source.readBytes()
        require(sourceBytes.size <= MAX_EXPORT_BYTES) {
            "This TXT file is too large to export safely on this device."
        }
        val sourceDigest = sha256(sourceBytes)
        val exportedBytes = TxtEditedCopySerializer.serialize(sourceBytes, editedText)
        require(source.canonicalFile.isFile && sourceDigest.contentEquals(source.inputStream().use { sha256(it) })) {
            "The source changed during export; no edited copy was written."
        }

        var outputOpened = false
        try {
            val output = application.contentResolver.openOutputStream(destination, "wt")
                ?: throw IllegalStateException("The selected location cannot be written to.")
            outputOpened = true
            output.use {
                it.write(exportedBytes)
                it.flush()
            }

            val actualDigest = application.contentResolver.openInputStream(destination)?.use { sha256(it) }
                ?: throw IllegalStateException("The exported copy could not be verified.")
            require(actualDigest.contentEquals(sha256(exportedBytes))) {
                "The saved copy did not match the edited text."
            }
        } catch (error: Exception) {
            if (outputOpened) {
                // SAF providers do not all support atomic writes; best-effort cleanup removes a partial new document.
                runCatching { application.contentResolver.delete(destination, null, null) }
            }
            throw error
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun sha256(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    private companion object {
        const val MAX_EXPORT_BYTES = 64 * 1024 * 1024
    }
}
