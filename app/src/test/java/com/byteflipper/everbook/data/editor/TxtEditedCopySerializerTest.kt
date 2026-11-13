package com.byteflipper.everbook.data.editor

import androidx.compose.ui.text.AnnotatedString
import com.byteflipper.everbook.domain.reader.ReaderText
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TxtEditedCopySerializerTest {
    @Test
    fun noOpExportPreservesEveryOriginalByte() {
        val source = "\uFEFFTitle  \r\n\r\nParagraph with trailing spaces  \r\n---\r\nLast line\r\n"
            .toByteArray(StandardCharsets.UTF_8)
        val entries = listOf(
            ReaderText.Chapter(title = "Title", nested = false),
            text("Paragraph with trailing spaces  "),
            ReaderText.Separator,
            text("Last line")
        )

        assertArrayEquals(source, TxtEditedCopySerializer.serialize(source, entries))
    }

    @Test
    fun deletingAnEmptyParagraphRemovesOnlyItsOriginalLineAndPreservesSource() {
        val source = "Title\r\nFirst paragraph\r\nSecond paragraph\r\n"
            .toByteArray(StandardCharsets.UTF_8)
        val originalBytes = source.copyOf()
        val entries = listOf(
            ReaderText.Chapter(title = "Title", nested = false),
            ReaderText.Text(AnnotatedString(""), source = "", isDeleted = true),
            text("Second paragraph")
        )

        val exported = TxtEditedCopySerializer.serialize(source, entries)

        assertEquals("Title\r\nSecond paragraph\r\n", exported.toString(StandardCharsets.UTF_8))
        assertArrayEquals(originalBytes, source)
    }

    @Test
    fun editsPatchOnlyMappedLinesAndPreserveMixedLineEndingsAndBlankLines() {
        val sourceText = "***\r\nTitle\n\nFirst paragraph  \rSecond paragraph\r\n"
        val source = sourceText.toByteArray(StandardCharsets.UTF_8)
        // TxtTextParser inserts the first chapter at index zero, before the earlier separator.
        val entries = listOf(
            ReaderText.Chapter(title = "New title", nested = false),
            ReaderText.Separator,
            text("First paragraph updated"),
            text("Second paragraph")
        )

        val actual = TxtEditedCopySerializer.serialize(source, entries)
            .toString(StandardCharsets.UTF_8)
        assertEquals("***\r\nNew title\n\nFirst paragraph updated\rSecond paragraph\r\n", actual)
    }

    @Test
    fun editedChapterTitleRetainsHeadingMarkupAndTrailingSpaces() {
        val source = "## **Old title**  \nParagraph\n".toByteArray(StandardCharsets.UTF_8)
        val entries = listOf(
            ReaderText.Chapter(title = "New title", nested = false),
            text("Paragraph")
        )

        val actual = TxtEditedCopySerializer.serialize(source, entries)
            .toString(StandardCharsets.UTF_8)

        assertEquals("## **New title**  \nParagraph\n", actual)
    }

    @Test
    fun utf16LittleEndianBomIsKeptAndOutputRemainsReadable() {
        val text = "العنوان\r\nفقرة قديمة\r\n"
        val encoded = text.toByteArray(StandardCharsets.UTF_16LE)
        val source = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + encoded
        val entries = listOf(
            ReaderText.Chapter(title = "العنوان", nested = false),
            text("فقرة جديدة")
        )

        val output = TxtEditedCopySerializer.serialize(source, entries)
        assertEquals(0xFF.toByte(), output[0])
        assertEquals(0xFE.toByte(), output[1])
        assertEquals("العنوان\r\nفقرة جديدة\r\n", output.copyOfRange(2, output.size).toString(StandardCharsets.UTF_16LE))
    }

    @Test
    fun rejectsLineBreaksAndReaderStructureMismatches() {
        val source = "Title\nParagraph\n".toByteArray(StandardCharsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            TxtEditedCopySerializer.serialize(
                source,
                listOf(
                    ReaderText.Chapter(title = "Title", nested = false),
                    text("Paragraph\nInjected line")
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TxtEditedCopySerializer.serialize(source, listOf(ReaderText.Chapter(title = "Title", nested = false)))
        }
    }

    @Test
    fun rejectsInvalidUtf8InsteadOfReplacingCharacters() {
        val invalid = byteArrayOf(0xC3.toByte(), 0x28)
        assertThrows(IllegalArgumentException::class.java) {
            TxtEditedCopySerializer.serialize(invalid, emptyList())
        }
    }

    private fun text(source: String) = ReaderText.Text(
        line = AnnotatedString(source),
        source = source
    )
}
