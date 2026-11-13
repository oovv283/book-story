/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.data.repository

import android.app.Application
import android.net.Uri
import com.anggrayudi.storage.file.DocumentFileCompat
import com.anggrayudi.storage.file.getAbsolutePath
import com.byteflipper.everbook.data.editor.EpubEditedCopySerializer
import com.byteflipper.everbook.data.editor.TxtEditedCopySerializer
import com.byteflipper.everbook.data.parser.TextParser
import com.byteflipper.everbook.domain.file.CachedFile
import com.byteflipper.everbook.domain.file.CachedFileCompat
import com.byteflipper.everbook.domain.library.book.Book
import com.byteflipper.everbook.domain.reader.ReaderText
import com.byteflipper.everbook.domain.repository.BookExportRepository
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class BookExportRepositoryImpl @Inject constructor(
    private val application: Application,
    private val textParser: TextParser
) : BookExportRepository {
    override suspend fun exportEditedCopy(
        book: Book,
        editedText: List<ReaderText>,
        destination: Uri
    ) = withContext(Dispatchers.IO) {
        require(destination.scheme == "content") {
            "Choose a writable document location."
        }

        val extension = book.filePath.substringBefore('?').substringAfterLast('.', "").lowercase()
        require(extension == "txt" || extension == "epub") {
            "Safe export is currently available for TXT and supported EPUB text edits only."
        }
        val source = resolveCachedFile(book.filePath)
        val sourceFile = source.rawFile
            ?: throw IllegalStateException("The source book is no longer available.")
        require(sourceFile.isFile && sourceFile.canRead()) {
            "The source book is no longer available."
        }
        require(sourceFile.length() <= MAX_EXPORT_BYTES) {
            "This book is too large to export safely on this device."
        }
        ensureDestinationIsSeparate(source, sourceFile, destination)

        val sourceDigest = source.openInputStream()?.use(::sha256)
            ?: throw IllegalStateException("The original book could not be verified before export.")
        val temporaryCopy = File(
            application.cacheDir,
            "everbook-edited-${UUID.randomUUID()}.$extension"
        )

        try {
            when (extension) {
                "txt" -> {
                    val sourceBytes = sourceFile.readBytes()
                    require(sourceBytes.size <= MAX_TXT_EXPORT_BYTES) {
                        "This TXT file is too large to export safely on this device."
                    }
                    val exportedBytes = TxtEditedCopySerializer.serialize(sourceBytes, editedText)
                    temporaryCopy.writeBytes(exportedBytes)
                }

                "epub" -> {
                    val originalText = textParser.parse(source)
                    require(originalText.isNotEmpty()) {
                        "The source EPUB could not be parsed for safe text mapping."
                    }
                    EpubEditedCopySerializer.write(
                        sourceFile = sourceFile,
                        destinationFile = temporaryCopy,
                        originalText = originalText,
                        editedText = editedText
                    )

                    val exportedFile = CachedFileCompat.fromFullPath(
                        context = application,
                        path = temporaryCopy.absolutePath
                    ) ?: throw IllegalStateException("The temporary EPUB could not be reopened for verification.")
                    val exportedText = textParser.parse(exportedFile)
                    require(readerTextMatches(editedText, exportedText)) {
                        "The exported EPUB did not reproduce the edited reading text; it was not saved to the selected location."
                    }
                }
            }

            val sourceDigestAfterExport = source.openInputStream()?.use(::sha256)
                ?: throw IllegalStateException("The original book could not be verified after export.")
            require(sourceDigest.contentEquals(sourceDigestAfterExport)) {
                "The original book changed during export; the separate copy was not written."
            }

            writeAndVerifyDocument(
                source = temporaryCopy,
                destination = destination,
                originalBook = source,
                originalBookDigest = sourceDigest
            )
        } finally {
            temporaryCopy.delete()
        }
    }

    private fun resolveCachedFile(path: String): CachedFile {
        val uri = Uri.parse(path)
        val localFile = File(path)
        val name = localFile.name.takeIf { it.isNotBlank() }
            ?: path.substringBefore('?').substringAfterLast('/')
        val builder = CachedFileCompat.build(
            name = name,
            path = path,
            size = localFile.takeIf { it.isFile }?.length(),
            lastModified = localFile.takeIf { it.isFile }?.lastModified(),
            isDirectory = false
        )
        val cachedFile = when (uri.scheme) {
            "content", "file" -> CachedFileCompat.fromUri(application, uri, builder)
            else -> CachedFileCompat.fromFullPath(application, path, builder)
        } ?: throw IllegalStateException("The source book is no longer accessible.")
        require(cachedFile.canAccess()) { "The source book is no longer accessible." }
        return cachedFile
    }

    private fun ensureDestinationIsSeparate(source: CachedFile, sourceFile: File, destination: Uri) {
        require(destination != source.uri) {
            "Choose a different file. The original book is never overwritten."
        }
        val destinationPath = runCatching {
            when (destination.scheme) {
                "file" -> destination.path
                "content" -> DocumentFileCompat.fromUri(application, destination)
                    ?.getAbsolutePath(application)
                else -> null
            }
        }.getOrNull()
        val sourceDocumentPath = runCatching {
            DocumentFileCompat.fromUri(application, source.uri)?.getAbsolutePath(application)
        }.getOrNull()
        val destinationCanonical = destinationPath?.let { runCatching { File(it).canonicalPath }.getOrNull() }
        val sourceCanonical = sourceDocumentPath?.let { runCatching { File(it).canonicalPath }.getOrNull() }
            ?: runCatching { sourceFile.canonicalPath }.getOrNull()
        require(destinationCanonical == null || sourceCanonical == null ||
            destinationCanonical != sourceCanonical
        ) {
            "Choose a different file. The original book is never overwritten."
        }
        require(destinationPath == null || destinationPath != source.path) {
            "Choose a different file. The original book is never overwritten."
        }
    }

    private fun writeAndVerifyDocument(
        source: File,
        destination: Uri,
        originalBook: CachedFile,
        originalBookDigest: ByteArray
    ) {
        val resolver = application.contentResolver
        val backup = File(application.cacheDir, "everbook-export-backup-${UUID.randomUUID()}.tmp")
        var hasBackup = false
        var outputOpened = false
        var backupDigest: ByteArray? = null
        try {
            val existing = resolver.openInputStream(destination)
                ?: throw IllegalStateException("The selected document cannot be read for safe updating.")
            existing.use { input ->
                FileOutputStream(backup).use { backupOutput ->
                    input.copyToLimited(backupOutput, MAX_EXPORT_BYTES)
                }
            }
            hasBackup = true
            backupDigest = backup.inputStream().use(::sha256)

            val destinationOutput = resolver.openOutputStream(destination, "wt")
                ?: throw IllegalStateException("The selected location cannot be written to.")
            outputOpened = true
            destinationOutput.use { output ->
                source.inputStream().use { input -> input.copyToLimited(output, MAX_EXPORT_BYTES) }
                output.flush()
            }

            val expectedDigest = source.inputStream().use(::sha256)
            val actualDigest = resolver.openInputStream(destination)?.use(::sha256)
                ?: throw IllegalStateException("The exported copy could not be verified.")
            require(expectedDigest.contentEquals(actualDigest)) {
                "The saved copy did not match the verified output."
            }
            val originalAfterWrite = originalBook.openInputStream()?.use(::sha256)
                ?: throw IllegalStateException("The original book could not be verified after export.")
            require(originalBookDigest.contentEquals(originalAfterWrite)) {
                "The destination refers to the original book; the previous file was restored instead."
            }
        } catch (error: Exception) {
            if (outputOpened && hasBackup) {
                runCatching {
                    val restoreOutput = resolver.openOutputStream(destination, "wt")
                        ?: throw IllegalStateException("Could not restore the previous saved copy.")
                    restoreOutput.use { output ->
                        backup.inputStream().use { input -> input.copyTo(output) }
                        output.flush()
                    }
                    val restoredDigest = resolver.openInputStream(destination)?.use(::sha256)
                        ?: throw IllegalStateException("Could not verify the restored destination.")
                    require(backupDigest?.contentEquals(restoredDigest) == true) {
                        "The previous destination could not be restored exactly."
                    }
                }.onFailure(error::addSuppressed)
            } else if (outputOpened) {
                // A newly-created partial document is removed if the provider supports deletion.
                runCatching { resolver.delete(destination, null, null) }
            }
            throw error
        } finally {
            backup.delete()
        }
    }

    private fun readerTextMatches(expected: List<ReaderText>, actual: List<ReaderText>): Boolean {
        if (expected.size != actual.size) return false
        return expected.indices.all { index ->
            when (val left = expected[index]) {
                is ReaderText.Chapter -> (actual[index] as? ReaderText.Chapter)?.let { right ->
                    left.title == right.title && left.nested == right.nested && left.isLinear == right.isLinear
                } == true
                is ReaderText.Text -> (actual[index] as? ReaderText.Text)?.source == left.source
                is ReaderText.Image -> actual[index] is ReaderText.Image
                ReaderText.Separator -> actual[index] == ReaderText.Separator
                is ReaderText.Math -> (actual[index] as? ReaderText.Math)?.latex == left.latex
            }
        }
    }

    private fun InputStream.copyToLimited(output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            copied += count
            require(copied <= limit) { "The exported document is too large." }
            output.write(buffer, 0, count)
        }
    }

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
        const val MAX_TXT_EXPORT_BYTES = 64L * 1024 * 1024
        const val MAX_EXPORT_BYTES = 128L * 1024 * 1024
    }
}
