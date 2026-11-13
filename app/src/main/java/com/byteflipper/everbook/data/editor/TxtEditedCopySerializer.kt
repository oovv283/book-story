/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.byteflipper.everbook.data.editor

import com.byteflipper.everbook.domain.reader.ReaderText
import com.byteflipper.everbook.presentation.core.util.clearAllMarkdown
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Writes an edited TXT copy by patching only the source lines represented in ReaderText.
 * Original files are never opened for writing. Unknown encodings, changed line structure,
 * or mismatched reader entries fail closed rather than producing a plausible but lossy file.
 */
internal object TxtEditedCopySerializer {
    private data class DecodedSource(
        val text: String,
        val charset: Charset,
        val bom: ByteArray
    )

    private data class SourceLine(
        val text: String,
        val lineEnding: String
    )

    private sealed interface ReaderEntryRef {
        data class Chapter(val sourceLine: Int) : ReaderEntryRef
        data class Text(val sourceLine: Int) : ReaderEntryRef
        data object Separator : ReaderEntryRef
    }

    fun serialize(sourceBytes: ByteArray, editedText: List<ReaderText>): ByteArray {
        val decoded = decode(sourceBytes)
        val lines = splitLines(decoded.text)
        val entryRefs = mapReaderEntries(lines)
        require(entryRefs.size == editedText.size) {
            "The book structure changed; export was cancelled to avoid applying edits to the wrong lines."
        }

        val outputLines = lines.toMutableList()
        val deletedSourceLines = mutableSetOf<Int>()
        var changed = false
        entryRefs.forEachIndexed { entryIndex, sourceRef ->
            val editedEntry = editedText[entryIndex]
            when (sourceRef) {
                ReaderEntryRef.Separator -> require(editedEntry is ReaderText.Separator) {
                    "The book structure changed; export was cancelled."
                }
                is ReaderEntryRef.Chapter -> {
                    require(editedEntry is ReaderText.Chapter) {
                        "The book structure changed; export was cancelled."
                    }
                    requireSingleSourceLine(editedEntry.title)
                    val oldLine = outputLines[sourceRef.sourceLine]
                    if (oldLine.text.clearAllMarkdown() != editedEntry.title) {
                        outputLines[sourceRef.sourceLine] = oldLine.copy(
                            text = replaceTitleSourceLine(oldLine.text, editedEntry.title)
                        )
                        changed = true
                    }
                }
                is ReaderEntryRef.Text -> {
                    require(editedEntry is ReaderText.Text) {
                        "The book structure changed; export was cancelled."
                    }
                    val oldLine = outputLines[sourceRef.sourceLine]
                    if (editedEntry.isDeleted) {
                        require(editedEntry.source.isBlank() && oldLine.text.isNotBlank()) {
                            "Only a mapped empty TXT paragraph can be deleted safely."
                        }
                        deletedSourceLines += sourceRef.sourceLine
                        changed = true
                    } else {
                        requireSingleSourceLine(editedEntry.source)
                        if (oldLine.text != editedEntry.source) {
                            outputLines[sourceRef.sourceLine] = oldLine.copy(text = editedEntry.source)
                            changed = true
                        }
                    }
                }
            }
        }

        deletedSourceLines.sortedDescending().forEach { lineIndex ->
            outputLines.removeAt(lineIndex)
        }

        // This exact-copy path also guarantees no-op round trips preserve every original byte.
        if (!changed) return sourceBytes.copyOf()

        val text = buildString {
            outputLines.forEach { line ->
                append(line.text)
                append(line.lineEnding)
            }
        }
        val encoded = try {
            val buffer = decoded.charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("The edited text cannot be represented in the source encoding.", error)
        }
        return ByteArrayOutputStream(decoded.bom.size + encoded.size).apply {
            write(decoded.bom)
            write(encoded)
        }.toByteArray()
    }

    private fun decode(bytes: ByteArray): DecodedSource {
        require(!bytes.startsWith(byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte())) &&
            !bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00))) {
            "UTF-32 TXT files are not supported for safe export."
        }
        val (charset, bom) = when {
            bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) ->
                StandardCharsets.UTF_8 to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) ->
                StandardCharsets.UTF_16LE to byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) ->
                StandardCharsets.UTF_16BE to byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> StandardCharsets.UTF_8 to byteArrayOf()
        }
        val offset = bom.size
        val text = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Only valid UTF-8 and BOM-marked UTF-16 TXT files can be safely exported.", error)
        }
        return DecodedSource(text, charset, bom)
    }

    private fun mapReaderEntries(lines: List<SourceLine>): List<ReaderEntryRef> {
        val entries = mutableListOf<ReaderEntryRef>()
        var chapterAdded = false
        lines.forEachIndexed { lineIndex, line ->
            if (line.text.isBlank()) return@forEachIndexed
            when (line.text) {
                "***", "---" -> entries += ReaderEntryRef.Separator
                else -> {
                    val title = line.text.clearAllMarkdown()
                    if (!chapterAdded && title.isNotBlank()) {
                        // TxtTextParser inserts the first chapter at index zero, before prior separators.
                        entries.add(0, ReaderEntryRef.Chapter(lineIndex))
                        chapterAdded = true
                    } else {
                        entries += ReaderEntryRef.Text(lineIndex)
                    }
                }
            }
        }
        return entries
    }

    private fun splitLines(text: String): List<SourceLine> {
        if (text.isEmpty()) return listOf(SourceLine("", ""))
        val lines = mutableListOf<SourceLine>()
        var start = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\r' -> {
                    val end = if (index + 1 < text.length && text[index + 1] == '\n') index + 2 else index + 1
                    lines += SourceLine(text.substring(start, index), text.substring(index, end))
                    start = end
                    index = end
                }
                '\n' -> {
                    lines += SourceLine(text.substring(start, index), "\n")
                    index += 1
                    start = index
                }
                else -> index += 1
            }
        }
        if (start < text.length) lines += SourceLine(text.substring(start), "")
        return lines.ifEmpty { listOf(SourceLine("", "")) }
    }

    private fun requireSingleSourceLine(value: String) {
        require('\n' !in value && '\r' !in value) {
            "Line breaks cannot be safely represented by the current TXT editor export."
        }
    }

    private fun replaceTitleSourceLine(source: String, title: String): String {
        val leadingWhitespace = source.takeWhile(Char::isWhitespace)
        val afterLeadingWhitespace = source.drop(leadingWhitespace.length)
        val headingMarks = afterLeadingWhitespace.takeWhile { it == '#' }
        val afterHeadingMarks = afterLeadingWhitespace.drop(headingMarks.length)
        val headingGap = afterHeadingMarks.takeWhile(Char::isWhitespace)
        val formattedTitle = afterHeadingMarks.drop(headingGap.length)
        val trailingWhitespace = formattedTitle.takeLastWhile(Char::isWhitespace)
        val content = formattedTitle.dropLast(trailingWhitespace.length)
        val wrapper = listOf("**", "__", "*", "_").firstOrNull {
            content.length >= it.length * 2 && content.startsWith(it) && content.endsWith(it)
        }.orEmpty()

        return leadingWhitespace + headingMarks + headingGap + wrapper + title + wrapper + trailingWhitespace
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
