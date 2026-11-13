/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.ui.book_editor

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byteflipper.everbook.domain.reader.ReaderText
import com.byteflipper.everbook.domain.reader.isEditableBookFormat
import com.byteflipper.everbook.domain.repository.BookRepository
import com.byteflipper.everbook.domain.use_case.book.GetBookById
import com.byteflipper.everbook.domain.use_case.book.ExportEditedBook
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
    private val bookRepository: BookRepository,
    private val exportEditedBook: ExportEditedBook
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
                val editHistory = bookRepository.getBookTextEditHistory(bookId)
                val savedCopyUri = bookRepository.getBookEditedCopyUri(bookId)
                savedText = text
                _state.value = BookEditorState(
                    book = book,
                    text = text,
                    isLoading = false,
                    editHistory = editHistory,
                    savedCopyUri = savedCopyUri
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
        if (current.isSaving || current.isExporting) return
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
        if (current.isSaving || current.isExporting) return
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
        if (current.isSaving || current.isExporting) return
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
        if (current.isSaving || current.isExporting) return
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
        if (current.isExporting || current.isSaving) return false
        if (!current.isDirty) return true
        val snapshot = current.text
        _state.value = current.copy(isSaving = true, errorMessage = null)

        return try {
            withContext(Dispatchers.IO) {
                bookRepository.saveBookTextEdits(book.id, snapshot, savedText)
            }
            savedText = snapshot
            val history = bookRepository.getBookTextEditHistory(book.id)
            _state.value = _state.value.copy(
                isSaving = false,
                isDirty = _state.value.text != savedText,
                editHistory = history,
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

    /** Exports the current editor snapshot to a separate document; it never replaces the source. */
    suspend fun exportCorrectedCopy(destination: Uri): Boolean {
        val current = _state.value
        val book = current.book ?: return false
        if (current.isSaving || current.isExporting || current.isLoading) return false
        val snapshot = current.text.toList()
        _state.value = current.copy(isExporting = true, errorMessage = null)

        return try {
            withContext(Dispatchers.IO) {
                exportEditedBook(book, snapshot, destination)
                bookRepository.setBookEditedCopyUri(book.id, destination.toString())
            }
            _state.value = _state.value.copy(
                isExporting = false,
                savedCopyUri = destination.toString(),
                savedCopyUpdateFailed = false,
                errorMessage = null
            )
            true
        } catch (_: Exception) {
            _state.value = _state.value.copy(
                isExporting = false
            )
            false
        }
    }

    suspend fun updateSavedCopy(): Boolean {
        val uri = _state.value.savedCopyUri ?: return false
        return exportCorrectedCopy(Uri.parse(uri))
    }

    suspend fun restoreOriginal(): Boolean {
        val current = _state.value
        val book = current.book ?: return false
        if (_state.value.isSaving || _state.value.isExporting) return false
        _state.value = _state.value.copy(isSaving = true, errorMessage = null)
        return try {
            bookRepository.clearBookTextEdits(book.id)
            val original = withContext(Dispatchers.IO) { getText.execute(book.id) }
            val history = bookRepository.getBookTextEditHistory(book.id)
            val savedCopyUri = bookRepository.getBookEditedCopyUri(book.id)
            val copyUpdateFailed = updateSavedCopySnapshot(book, original, savedCopyUri)
            savedText = original
            _state.value = BookEditorState(
                book = book,
                text = original,
                isLoading = false,
                editHistory = history,
                savedCopyUri = savedCopyUri,
                savedCopyUpdateFailed = copyUpdateFailed
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

    suspend fun restoreRevision(revisionId: String): Boolean {
        val current = _state.value
        val book = current.book ?: return false
        if (current.isSaving || current.isExporting || current.isLoading) return false
        _state.value = current.copy(isSaving = true, errorMessage = null)

        return try {
            val restored = withContext(Dispatchers.IO) {
                bookRepository.getBookTextEditRevision(book.id, revisionId)
                    ?: throw IllegalStateException("This saved version is no longer available.")
            }
            withContext(Dispatchers.IO) {
                bookRepository.preserveCurrentBookTextEditsBeforeRestore(book.id)
                bookRepository.saveBookTextEdits(book.id, restored, current.text)
            }
            val history = bookRepository.getBookTextEditHistory(book.id)
            val copyUpdateFailed = updateSavedCopySnapshot(book, restored, current.savedCopyUri)
            savedText = restored
            _state.value = current.copy(
                text = restored,
                isSaving = false,
                isDirty = false,
                undoStack = emptyList(),
                redoStack = emptyList(),
                editHistory = history,
                savedCopyUpdateFailed = copyUpdateFailed,
                errorMessage = null
            )
            lastEditAt = 0L
            lastChangedIndex = null
            true
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                isSaving = false,
                errorMessage = error.message ?: "Could not restore this saved version."
            )
            false
        }
    }

    private suspend fun updateSavedCopySnapshot(
        book: com.byteflipper.everbook.domain.library.book.Book,
        text: List<ReaderText>,
        uri: String?
    ): Boolean {
        val extension = book.filePath.substringBefore('?').substringAfterLast('.', "")
            .lowercase()
        if (uri == null || extension !in setOf("txt", "epub")) return false
        return try {
            withContext(Dispatchers.IO) {
                exportEditedBook(book, text, Uri.parse(uri))
            }
            false
        } catch (_: Exception) {
            true
        }
    }

    private companion object {
        const val CHANGE_GROUP_WINDOW_MS = 700L
    }
}
