package com.byteflipper.everbook.data.editor

import androidx.compose.ui.text.AnnotatedString
import com.byteflipper.everbook.data.parser.MarkdownParser
import com.byteflipper.everbook.domain.reader.ReaderText
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTextEditMappingTest {
    private val markdownParser = MarkdownParser(Parser.builder().build())

    @Test
    fun deletedTextIsStoredAtItsOriginalIndexAndCanBeRestoredFromHistory() {
        val original = listOf(
            ReaderText.Chapter(title = "Chapter", nested = false),
            text("First paragraph"),
            text("Following paragraph")
        )
        val deletion = BookTextEditEntry(index = 1, kind = "deleted_text", value = "")

        val applied = applyBookTextEditEntries(
            sourceText = original,
            edits = mapOf(1 to deletion),
            parseMarkdown = markdownParser::parse
        )
        val storedEntries = readerTextToBookTextEditEntries(applied)

        assertEquals(original.size, applied.size)
        assertTrue((applied[1] as ReaderText.Text).isDeleted)
        assertEquals("", (applied[1] as ReaderText.Text).source)
        assertEquals("Following paragraph", (applied[2] as ReaderText.Text).source)
        assertEquals("First paragraph", (original[1] as ReaderText.Text).source)
        assertEquals(1, storedEntries[1].index)
        assertEquals("deleted_text", storedEntries[1].kind)

        val restored = applyBookTextEditEntries(
            sourceText = original,
            edits = mapOf(1 to BookTextEditEntry(index = 1, kind = "text", value = "")),
            parseMarkdown = markdownParser::parse
        )
        assertFalse((restored[1] as ReaderText.Text).isDeleted)
        assertEquals("", (restored[1] as ReaderText.Text).source)
        assertEquals("Following paragraph", (restored[2] as ReaderText.Text).source)
    }

    private fun text(value: String) = ReaderText.Text(
        line = AnnotatedString(value),
        source = value
    )
}
