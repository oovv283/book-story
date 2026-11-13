/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.data.editor

import com.byteflipper.everbook.domain.reader.ReaderText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * Creates a new EPUB while preserving every archive entry except XHTML paragraphs that can be
 * mapped one-to-one to plain editable text. Ambiguous, formatted, or structural edits fail closed
 * instead of risking the source package or its assets.
 */
internal object EpubEditedCopySerializer {
    private data class TextChange(
        val original: String,
        val replacement: String,
        val removeParagraph: Boolean = false
    )

    private data class TextLocation(
        val entryName: String,
        val nodeIndex: Int
    )

    private data class ParsedXhtml(
        val document: Document,
        val textNodes: List<TextNode>
    )

    private val editableBlockTags = setOf(
        "p", "li", "blockquote", "pre", "td", "th", "figcaption", "dt", "dd"
    )
    private val markdownSyntax = Regex(
        """\*\*\*\s*.+?\s*\*\*\*|\*\*\s*.+?\s*\*\*|_\s*.+?\s*_|\[\[.+?\|.+?]]|^\s*(---|\*\*\*)\s*$""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    fun write(
        sourceFile: File,
        destinationFile: File,
        originalText: List<ReaderText>,
        editedText: List<ReaderText>
    ) {
        require(sourceFile.isFile && sourceFile.canRead()) { "The source EPUB is unavailable." }
        require(sourceFile.canonicalFile != destinationFile.canonicalFile) {
            "The edited EPUB must be saved to a separate file; the source is never overwritten."
        }
        require(sourceFile.length() <= MAX_ARCHIVE_BYTES) {
            "This EPUB is too large to export safely on this device."
        }

        val changes = collectChanges(originalText, editedText)
        if (changes.isEmpty()) {
            destinationFile.parentFile?.mkdirs()
            sourceFile.copyTo(destinationFile, overwrite = true)
            verifyArchive(sourceFile, destinationFile, emptySet())
            return
        }

        val destinationEntries = mutableMapOf<String, ByteArray>()
        val locations = MutableList<TextLocation?>(changes.size) { null }
        val changeIndexesByText = changes.indices.groupBy { normalizeText(changes[it].original) }
        val documentsByName = mutableMapOf<String, ParsedXhtml>()

        ZipFile(sourceFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.isNotEmpty() && entries.first().name == MIMETYPE_ENTRY) {
                "This file does not have the required EPUB package order."
            }
            require(entries.map { it.name }.distinct().size == entries.size) {
                "This EPUB contains duplicate archive paths and cannot be safely rewritten."
            }
            val mimetype = readEntry(zip, entries.first(), MAX_XHTML_ENTRY_BYTES)
            require(entries.first().method == ZipEntry.STORED &&
                String(mimetype, StandardCharsets.US_ASCII) == EPUB_MIMETYPE
            ) {
                "This EPUB has an invalid mimetype entry; export was cancelled to preserve it."
            }

            val xhtmlEntries = entries.filter { isXhtml(it.name) }
            val totalXhtmlBytes = xhtmlEntries.sumOf { it.size.coerceAtLeast(0L) }
            require(totalXhtmlBytes <= MAX_TOTAL_XHTML_BYTES) {
                "This EPUB has too much XHTML text to map safely on this device."
            }

            var totalXhtmlBytesRead = 0L
            xhtmlEntries.forEach { entry ->
                require(entry.size <= MAX_XHTML_ENTRY_BYTES) {
                    "An XHTML file in this EPUB is too large to rewrite safely."
                }
                val bytes = readEntry(zip, entry, MAX_XHTML_ENTRY_BYTES)
                totalXhtmlBytesRead += bytes.size
                require(totalXhtmlBytesRead <= MAX_TOTAL_XHTML_BYTES) {
                    "This EPUB has too much XHTML text to map safely on this device."
                }
                val parsed = parseXhtml(bytes, entry.name)
                parsed.textNodes.forEachIndexed { nodeIndex, textNode ->
                    val parent = textNode.parent() as? org.jsoup.nodes.Element ?: return@forEachIndexed
                    if (parent.tagName().lowercase() !in editableBlockTags ||
                        parent.childNodes().size != 1 || parent.childNode(0) !== textNode
                    ) {
                        return@forEachIndexed
                    }

                    val normalized = normalizeText(textNode.wholeText)
                    val matchingChanges = changeIndexesByText[normalized].orEmpty()
                    matchingChanges.forEach { changeIndex ->
                        require(locations[changeIndex] == null) {
                            "The text to change appears more than once in the EPUB; export was cancelled rather than editing the wrong passage."
                        }
                        locations[changeIndex] = TextLocation(entry.name, nodeIndex)
                        documentsByName[entry.name] = parsed
                    }
                }
            }

            require(locations.all { it != null }) {
                "At least one edited passage is formatted, split across elements, or could not be mapped uniquely to its original EPUB paragraph. The source and assets were left unchanged."
            }

            changes.forEachIndexed { index, change ->
                val location = checkNotNull(locations[index])
                val parsed = checkNotNull(documentsByName[location.entryName])
                val node = parsed.textNodes.getOrNull(location.nodeIndex)
                    ?: throw IllegalArgumentException("The EPUB text mapping changed during export.")
                if (change.removeParagraph) {
                    val paragraph = node.parent() as? org.jsoup.nodes.Element
                        ?: throw IllegalArgumentException("The EPUB paragraph could not be removed safely.")
                    require(
                        paragraph.tagName().equals("p", ignoreCase = true) &&
                            !paragraph.hasAttr("id") &&
                            !paragraph.hasAttr("xml:id") &&
                            !paragraph.hasAttr("name")
                    ) {
                        "Only a plain, unlinked EPUB paragraph can be removed safely; its edit remains saved in the app."
                    }
                    paragraph.remove()
                } else {
                    node.text(change.replacement)
                }
            }

            documentsByName.forEach { (entryName, parsed) ->
                val outputSettings = parsed.document.outputSettings()
                outputSettings
                    .prettyPrint(false)
                    .syntax(Document.OutputSettings.Syntax.xml)
                    .charset(StandardCharsets.UTF_8)
                destinationEntries[entryName] = parsed.document.outerHtml()
                    .toByteArray(StandardCharsets.UTF_8)
            }

            writeArchive(destinationFile, zip, entries, destinationEntries)
        }

        verifyArchive(sourceFile, destinationFile, destinationEntries.keys)
    }

    private fun collectChanges(
        originalText: List<ReaderText>,
        editedText: List<ReaderText>
    ): List<TextChange> {
        require(originalText.size == editedText.size) {
            "The EPUB text structure changed; export was cancelled to avoid shifting edits onto other passages."
        }

        val changes = mutableListOf<TextChange>()
        originalText.indices.forEach { index ->
            val original = originalText[index]
            val edited = editedText[index]
            when {
                original is ReaderText.Text && edited is ReaderText.Text -> {
                    if (edited.isDeleted) {
                        require(edited.source.isBlank()) {
                            "Only an empty EPUB paragraph can be deleted."
                        }
                        require(original.source.isNotBlank()) {
                            "This EPUB paragraph has no unique source text to map for safe deletion."
                        }
                        changes += TextChange(
                            original = original.source,
                            replacement = "",
                            removeParagraph = true
                        )
                    } else if (original.source != edited.source) {
                        require(edited.source.isNotBlank()) {
                            "An EPUB paragraph cannot be emptied safely; keep at least one character or clear it only in the app."
                        }
                        require('\n' !in edited.source && '\r' !in edited.source) {
                            "Line breaks cannot be added to an EPUB paragraph safely yet."
                        }
                        require(edited.source.length <= MAX_TEXT_LENGTH) {
                            "The edited paragraph is too long to export safely."
                        }
                        require(!markdownSyntax.containsMatchIn(edited.source)) {
                            "This EPUB exporter supports plain-text paragraph changes only; Markdown formatting changes remain saved in the app."
                        }
                        changes += TextChange(original.source, edited.source)
                    }
                }
                original is ReaderText.Chapter && edited is ReaderText.Chapter -> {
                    require(original.title == edited.title && original.nested == edited.nested &&
                        original.isLinear == edited.isLinear
                    ) {
                        "EPUB chapter titles affect the navigation package and are not yet safe to export. Text edits remain saved in the app."
                    }
                }
                original is ReaderText.Separator && edited is ReaderText.Separator -> Unit
                original is ReaderText.Image && edited is ReaderText.Image -> Unit
                original is ReaderText.Math && edited is ReaderText.Math -> {
                    require(original.latex == edited.latex) {
                        "MathML/formula changes cannot be mapped safely to this EPUB yet."
                    }
                }
                else -> throw IllegalArgumentException(
                    "The EPUB content structure changed; export was cancelled to avoid altering package assets."
                )
            }
        }

        require(changes.map { normalizeText(it.original) }.distinct().size == changes.size) {
            "Two edited paragraphs have the same original text; export was cancelled because their locations are ambiguous."
        }
        return changes
    }

    private fun parseXhtml(bytes: ByteArray, entryName: String): ParsedXhtml {
        val prefix = String(bytes, 0, minOf(bytes.size, XML_HEADER_LIMIT), StandardCharsets.UTF_8)
        val declaredEncoding = XML_ENCODING.find(prefix)?.groupValues?.getOrNull(2)
        require(declaredEncoding == null || declaredEncoding.equals("utf-8", ignoreCase = true)) {
            "The EPUB XHTML uses a non-UTF-8 encoding; export was cancelled to avoid changing text bytes incorrectly."
        }
        require(!bytes.startsWith(UTF16_LE_BOM) && !bytes.startsWith(UTF16_BE_BOM)) {
            "The EPUB XHTML uses UTF-16; safe export is not supported for that encoding yet."
        }

        val document = Jsoup.parse(
            ByteArrayInputStream(bytes),
            StandardCharsets.UTF_8.name(),
            entryName,
            Parser.xmlParser()
        )
        require(document.selectFirst("html") != null && document.selectFirst("body") != null) {
            "An XHTML package document could not be safely parsed."
        }
        val textNodes = mutableListOf<TextNode>()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is TextNode) textNodes += node
            }

            override fun tail(node: Node, depth: Int) = Unit
        }, document)
        return ParsedXhtml(document, textNodes)
    }

    private fun writeArchive(
        destinationFile: File,
        sourceZip: ZipFile,
        entries: List<ZipEntry>,
        replacements: Map<String, ByteArray>
    ) {
        destinationFile.parentFile?.mkdirs()
        val temporary = File(destinationFile.parentFile, "${destinationFile.name}.${UUID.randomUUID()}.tmp")
        try {
            ZipOutputStream(FileOutputStream(temporary)).use { output ->
                entries.forEach { sourceEntry ->
                    val replacement = replacements[sourceEntry.name]
                    val outputEntry = ZipEntry(sourceEntry.name).apply {
                        time = sourceEntry.time
                        comment = sourceEntry.comment
                        if (sourceEntry.name == MIMETYPE_ENTRY) {
                            method = ZipEntry.STORED
                            size = sourceEntry.size
                            compressedSize = sourceEntry.size
                            crc = sourceEntry.crc
                        } else if (replacement == null && sourceEntry.method == ZipEntry.STORED) {
                            method = ZipEntry.STORED
                            size = sourceEntry.size
                            compressedSize = sourceEntry.size
                            crc = sourceEntry.crc
                        } else {
                            method = ZipEntry.DEFLATED
                        }
                    }
                    output.putNextEntry(outputEntry)
                    if (replacement != null) {
                        output.write(replacement)
                    } else {
                        sourceZip.getInputStream(sourceEntry).use { input -> input.copyTo(output) }
                    }
                    output.closeEntry()
                }
            }

            try {
                Files.move(
                    temporary.toPath(),
                    destinationFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                Files.move(
                    temporary.toPath(),
                    destinationFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun verifyArchive(sourceFile: File, outputFile: File, changedEntries: Set<String>) {
        require(outputFile.isFile && outputFile.length() > 0L) { "The EPUB copy could not be written." }
        ZipFile(sourceFile).use { source ->
            ZipFile(outputFile).use { output ->
                val sourceEntries = source.entries().asSequence().toList()
                val outputEntries = output.entries().asSequence().toList()
                require(sourceEntries.map { it.name } == outputEntries.map { it.name }) {
                    "The exported EPUB package is missing or reordering archive entries."
                }
                require(outputEntries.firstOrNull()?.name == MIMETYPE_ENTRY &&
                    outputEntries.first().method == ZipEntry.STORED
                ) {
                    "The exported EPUB does not have a valid mimetype entry."
                }
                sourceEntries.forEachIndexed { index, sourceEntry ->
                    if (sourceEntry.name in changedEntries) {
                        val outputBytes = readEntry(output, outputEntries[index], MAX_XHTML_ENTRY_BYTES)
                        val parsed = parseXhtml(outputBytes, sourceEntry.name)
                        require(parsed.document.selectFirst("html") != null) {
                            "An edited XHTML file did not pass validation."
                        }
                    } else {
                        val sourceDigest = source.getInputStream(sourceEntry).use(::sha256)
                        val outputDigest = output.getInputStream(outputEntries[index]).use(::sha256)
                        require(sourceDigest.contentEquals(outputDigest)) {
                            "An unchanged EPUB resource differed after export; the copy was rejected."
                        }
                    }
                }
            }
        }
    }

    private fun readEntry(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray =
        zip.getInputStream(entry).use { input -> input.readBytesLimited(limit) }

    private fun InputStream.readBytesLimited(limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "An EPUB package entry is too large to export safely." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(input: InputStream): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    private fun normalizeText(value: String): String =
        value.replace(WHITESPACE, " ").trim()

    private fun isXhtml(path: String): Boolean =
        path.endsWith(".xhtml", ignoreCase = true) ||
            path.endsWith(".html", ignoreCase = true) ||
            path.endsWith(".htm", ignoreCase = true)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private const val MIMETYPE_ENTRY = "mimetype"
    private const val EPUB_MIMETYPE = "application/epub+zip"
    private const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
    private const val MAX_XHTML_ENTRY_BYTES = 8L * 1024 * 1024
    private const val MAX_TOTAL_XHTML_BYTES = 64L * 1024 * 1024
    private const val MAX_TEXT_LENGTH = 100_000
    private const val XML_HEADER_LIMIT = 1024
    private val WHITESPACE = Regex("\\s+")
    private val XML_ENCODING = Regex("""(?i)encoding\s*=\s*(['\"])([^'\"]+)\1""")
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
}
