/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.data.editor

import androidx.compose.ui.text.AnnotatedString
import com.byteflipper.everbook.domain.reader.ReaderText
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubEditedCopySerializerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exportsEditedEpubWithoutChangingOriginalOrPackageAssets() {
        val source = File(temporaryFolder.root, "source.epub")
        val output = File(temporaryFolder.root, "edited.epub")
        val imageBytes = byteArrayOf(0, 1, 2, 3, 4, 5)
        createEpub(source, imageBytes)
        val originalBytes = source.readBytes()

        EpubEditedCopySerializer.write(
            sourceFile = source,
            destinationFile = output,
            originalText = listOf(
                ReaderText.Chapter(title = "Chapter 1", nested = false),
                text("Original passage.")
            ),
            editedText = listOf(
                ReaderText.Chapter(title = "Chapter 1", nested = false),
                text("Updated & safe passage.")
            )
        )

        assertArrayEquals("The source EPUB must remain byte-for-byte unchanged.", originalBytes, source.readBytes())
        ZipFile(source).use { original ->
            ZipFile(output).use { edited ->
                val originalNames = original.entries().asSequence().map { it.name }.toList()
                val editedEntries = edited.entries().asSequence().toList()
                assertEquals(originalNames, editedEntries.map { it.name })
                assertEquals("mimetype", editedEntries.first().name)
                assertEquals(ZipEntry.STORED, editedEntries.first().method)
                assertArrayEquals(imageBytes, edited.getInputStream(edited.getEntry("OEBPS/images/pixel.png")).readBytes())
                assertArrayEquals(
                    original.getInputStream(original.getEntry("OEBPS/styles.css")).readBytes(),
                    edited.getInputStream(edited.getEntry("OEBPS/styles.css")).readBytes()
                )
                val xhtml = edited.getInputStream(edited.getEntry("OEBPS/text/chapter.xhtml"))
                    .bufferedReader().use { it.readText() }
                val parsed = Jsoup.parse(xhtml, "", Parser.xmlParser())
                assertEquals("Updated & safe passage.", parsed.selectFirst("p")?.text())
                assertNotNull(parsed.selectFirst("body"))
            }
        }
    }

    @Test
    fun removesDeletedEmptyParagraphFromEditedCopyAndKeepsOriginalAndAssets() {
        val source = File(temporaryFolder.root, "source-delete.epub")
        val output = File(temporaryFolder.root, "edited-delete.epub")
        val imageBytes = byteArrayOf(9, 8, 7, 6)
        createEpub(source, imageBytes, paragraphId = null)
        val originalBytes = source.readBytes()

        EpubEditedCopySerializer.write(
            sourceFile = source,
            destinationFile = output,
            originalText = listOf(
                ReaderText.Chapter(title = "Chapter 1", nested = false),
                text("Original passage.")
            ),
            editedText = listOf(
                ReaderText.Chapter(title = "Chapter 1", nested = false),
                text("", isDeleted = true)
            )
        )

        assertArrayEquals("The source EPUB must remain byte-for-byte unchanged.", originalBytes, source.readBytes())
        ZipFile(output).use { edited ->
            assertArrayEquals(imageBytes, edited.getInputStream(edited.getEntry("OEBPS/images/pixel.png")).readBytes())
            val xhtml = edited.getInputStream(edited.getEntry("OEBPS/text/chapter.xhtml"))
                .bufferedReader().use { it.readText() }
            val parsed = Jsoup.parse(xhtml, "", Parser.xmlParser())
            assertTrue(parsed.select("body p").isEmpty())
            assertNotNull(parsed.selectFirst("body"))
        }
    }

    @Test
    fun noOpExportIsAnExactByteCopy() {
        val source = File(temporaryFolder.root, "source-no-op.epub")
        val output = File(temporaryFolder.root, "copy-no-op.epub")
        createEpub(source, byteArrayOf(7, 8, 9))
        val sourceBytes = source.readBytes()
        val text = listOf(ReaderText.Chapter(title = "Chapter 1", nested = false), text("Original passage."))

        EpubEditedCopySerializer.write(source, output, text, text)

        assertArrayEquals(sourceBytes, output.readBytes())
        assertArrayEquals(sourceBytes, source.readBytes())
    }

    @Test
    fun refusesDuplicatePassagesInsteadOfEditingTheWrongOne() {
        val source = File(temporaryFolder.root, "duplicate.epub")
        val output = File(temporaryFolder.root, "duplicate-edited.epub")
        createEpub(source, byteArrayOf(1), duplicateParagraph = true)
        val bytesBefore = source.readBytes()
        val sourceText = listOf(
            ReaderText.Chapter(title = "Chapter 1", nested = false),
            text("Original passage.")
        )
        val editedText = listOf(
            ReaderText.Chapter(title = "Chapter 1", nested = false),
            text("Replacement passage.")
        )

        val error = runCatching {
            EpubEditedCopySerializer.write(source, output, sourceText, editedText)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertArrayEquals(bytesBefore, source.readBytes())
        assertTrue(!output.exists())
    }

    @Test
    fun refusesChangesToFormattedMarkdownSource() {
        val source = File(temporaryFolder.root, "formatted.epub")
        val output = File(temporaryFolder.root, "formatted-edited.epub")
        createEpub(source, byteArrayOf(2))
        val sourceText = listOf(
            ReaderText.Chapter(title = "Chapter 1", nested = false),
            text("**Original passage.**")
        )
        val editedText = listOf(
            ReaderText.Chapter(title = "Chapter 1", nested = false),
            text("**Updated passage.**")
        )

        val error = runCatching {
            EpubEditedCopySerializer.write(source, output, sourceText, editedText)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(!output.exists())
    }

    private fun createEpub(
        file: File,
        imageBytes: ByteArray,
        duplicateParagraph: Boolean = false,
        paragraphId: String? = "p1"
    ) {
        val duplicate = if (duplicateParagraph) "<p id=\"p2\">Original passage.</p>" else ""
        val primaryParagraphId = paragraphId?.let { " id=\"$it\"" }.orEmpty()
        val xhtml = """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter 1</title></head><body><p$primaryParagraphId>Original passage.</p>$duplicate</body></html>"""
        ZipOutputStream(file.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(mimetype) }
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = mimetype.size.toLong()
                this.crc = crc.value
            })
            zip.write(mimetype)
            zip.closeEntry()
            put(zip, "META-INF/container.xml", """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put(zip, "OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><manifest><item id="chapter" href="text/chapter.xhtml" media-type="application/xhtml+xml"/><item id="image" href="images/pixel.png" media-type="image/png"/><item id="style" href="styles.css" media-type="text/css"/></manifest><spine><itemref idref="chapter"/></spine></package>""")
            put(zip, "OEBPS/text/chapter.xhtml", xhtml)
            zip.putNextEntry(ZipEntry("OEBPS/images/pixel.png"))
            zip.write(imageBytes)
            zip.closeEntry()
            put(zip, "OEBPS/styles.css", "body { direction: rtl; }")
        }
    }

    private fun put(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun text(value: String, isDeleted: Boolean = false) = ReaderText.Text(
        line = AnnotatedString(value),
        source = value,
        isDeleted = isDeleted
    )
}
