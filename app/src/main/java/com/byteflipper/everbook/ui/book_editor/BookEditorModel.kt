/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.ui.book_editor

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byteflipper.everbook.domain.reader.ReaderText
import com.byteflipper.everbook.domain.reader.isEditableBookFormat
import com.byteflipper.everbook.domain.repository.BookRepository
import com.byteflipper.everbook.domain.use_case.book.GetBookById
import com.byteflipper.everbook.domain.use_case.book.GetText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class BookEditorModel @Inject constructor(
    private val getBookById: GetBookById,
    private val getText: GetText,
    private val bookRepository: BookRepository
) : ViewModel() {
    private val _state = MutableStateFlow(BookEditorState())
    internal val state = _state.asStateFlow()

    private var savedText: List<ReaderText> = emptyList()
    private var lastEditAt = 0L
    private var lastChangedIndex: Int? = null

    fun load(bookId: Int) {
        if (_state.value.book?.id == bookId && !_state.value.isLoading) return
        _state.value = BookEditorState(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val book = getBookById.execute(bookId)
                    ?: throw IllegalStateException("The book is no longer in the library.")
                if (!book.filePath.isEditableBookFormat()) {
                    throw UnsupportedOperationException("This book format cannot be edited.")
                }
                val text = getText.execute(bookId)
                if (text.isEmpty()) {
                    throw IllegalStateException("No editable text could be read from this book.")
                }
                savedText = text
                _state.value = BookEditorState(
                    book = book,
                    text = text,
                    isLoading = false
                )
            } catch (error: Exception) {
                _state.value = BookEditorState(
                    isLoading = false,
                    errorMessage = error.message ?: "Could not open the editor."
                )
            }
        }
    }

    fun changeText(index: Int, value: String) {
        val current = _state.value
        val item = current.text.getOrNull(index) ?: return
        if (item.editorSource() == value) return

        val now = SystemClock.elapsedRealtime()
        val canGroupWithLastChange = now - lastEditAt <= CHANGE_GROUP_WINDOW_MS &&
            lastChangedIndex == index
        val undo = if (canGroupWithLastChange) {
            current.undoStack
        } else {
            current.undoStack + listOf(current.text)
        }
        val updated = current.text.toMutableList().also {
            it[index] = item.withEditorSource(value)
        }.toList()

        lastEditAt = now
        lastChangedIndex = index
        _state.value = current.copy(
            text = updated,
            isDirty = updated != savedText,
            undoStack = undo,
            redoStack = emptyList(),
            errorMessage = null
        )
    }

    /** Applies a multi-field edit (such as Replace Selected) as one undoable action. */
    fun applyBatch(updatedText: List<ReaderText>) {
        val current = _state.value
        if (updatedText == current.text) return
        _state.value = current.copy(
            text = updatedText,
            isDirty = updatedText != savedText,
            undoStack = current.undoStack + listOf(current.text),
            redoStack = emptyList(),
            errorMessage = null
        )
        lastEditAt = 0L
        lastChangedIndex = null
    }

    fun undo() {
        val current = _state.value
        val target = current.undoStack.lastOrNull() ?: return
        _state.value = current.copy(
            text = target,
            isDirty = target != savedText,
            undoStack = current.undoStack.dropLast(1),
            redoStack = current.redoStack + listOf(current.text),
            errorMessage = null
        )
        lastEditAt = 0L
        lastChangedIndex = null
    }

    fun redo() {
        val current = _state.value
        val target = current.redoStack.lastOrNull() ?: return
        _state.value = current.copy(
            text = target,
            isDirty = target != savedText,
            undoStack = current.undoStack + listOf(current.text),
            redoStack = current.redoStack.dropLast(1),
            errorMessage = null
        )
        lastEditAt = 0L
        lastChangedIndex = null
    }

    suspend fun save(): Boolean {
        val current = _state.value
        val book = current.book ?: return false
        if (!current.isDirty) return true
        val snapshot = current.text
        _state.value = current.copy(isSaving = true, errorMessage = null)

        return try {
            withContext(Dispatchers.IO) {
                bookRepository.saveBookTextEdits(book.id, snapshot)
            }
            savedText = snapshot
            _state.value = _state.value.copy(
                isSaving = false,
                isDirty = _state.value.text != savedText,
                errorMessage = null
            )
            true
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                isSaving = false,
                errorMessage = error.message ?: "Could not save these edits."
            )
            false
        }
    }

    suspend fun restoreOriginal(): Boolean {
        val book = _state.value.book ?: return false
        _state.value = _state.value.copy(isSaving = true, errorMessage = null)
        return try {
            bookRepository.clearBookTextEdits(book.id)
            val original = withContext(Dispatchers.IO) { getText.execute(book.id) }
            savedText = original
            _state.value = BookEditorState(
                book = book,
                text = original,
                isLoading = false
            )
            lastEditAt = 0L
            lastChangedIndex = null
            true
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                isSaving = false,
                errorMessage = error.message ?: "Could not restore the source text."
            )
            false
        }
    }

    private companion object {
        const val CHANGE_GROUP_WINDOW_MS = 700L
    }
}
