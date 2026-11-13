package com.byteflipper.everbook.ui.book_editor

import androidx.compose.ui.text.AnnotatedString
import com.byteflipper.everbook.domain.reader.ReaderText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookEditorTextOperationsTest {
    @Test
    fun deletingAnEmptyParagraphKeepsSourceIndexesAndCreatesUndoSnapshot() {
        val source = listOf(
            ReaderText.Chapter(title = "Chapter", nested = false),
            text("Before"),
            text(" \t"),
            text("After")
        )
        val state = BookEditorState(text = source, redoStack = listOf(source))

        val updated = state.withEmptyParagraphDeletedAt(index = 2, savedText = source)

        assertNotNull(updated)
        assertEquals(source.size, updated!!.text.size)
        assertTrue((updated.text[2] as ReaderText.Text).isDeleted)
        assertEquals(source[3], updated.text[3])
        assertEquals("The pre-delete text is the Undo target.", source, updated.undoStack.last())
        assertTrue(updated.redoStack.isEmpty())
        assertTrue(updated.isDirty)
        assertFalse((source[2] as ReaderText.Text).isDeleted)
    }

    @Test
    fun refusesToDeleteNonEmptyEntriesOrTheSameDeletedParagraphTwice() {
        val source = listOf(
            ReaderText.Chapter(title = "Chapter", nested = false),
            text("Paragraph"),
            text("")
        )
        val onceDeleted = source.withEmptyParagraphDeleted(index = 2)!!

        assertNull(source.withEmptyParagraphDeleted(index = 0))
        assertNull(source.withEmptyParagraphDeleted(index = 1))
        assertNull(onceDeleted.withEmptyParagraphDeleted(index = 2))
    }

    private fun text(value: String) = ReaderText.Text(
        line = AnnotatedString(value),
        source = value
    )
}
