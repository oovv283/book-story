/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.ui.book_editor

import android.os.Parcelable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.byteflipper.everbook.R
import com.byteflipper.everbook.domain.navigator.Screen
import com.byteflipper.everbook.domain.reader.ReaderText
import com.byteflipper.everbook.presentation.core.util.showToast
import com.byteflipper.everbook.presentation.navigator.LocalNavigator
import com.byteflipper.everbook.ui.reader.ReaderScreen
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

@Parcelize
data class BookEditorScreen(
    val bookId: Int,
    val returnToReader: Boolean = false
) : Screen, Parcelable {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = hiltViewModel<BookEditorModel>()
        val state by model.state.collectAsStateWithLifecycle()

        LaunchedEffect(bookId) {
            model.load(bookId)
        }

        BookEditorContent(
            bookId = bookId,
            returnToReader = returnToReader,
            state = state,
            model = model,
            onNavigateBack = { navigator.pop() }
        )
    }
}

private data class SearchJump(
    val match: BookEditorMatch,
    val sequence: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookEditorContent(
    bookId: Int,
    returnToReader: Boolean,
    state: BookEditorState,
    model: BookEditorModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSearch by rememberSaveable(bookId) { mutableStateOf(false) }
    var showSearchReturn by rememberSaveable(bookId) { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable(bookId) { mutableStateOf("") }
    var replacement by rememberSaveable(bookId) { mutableStateOf("") }
    var caseSensitive by rememberSaveable(bookId) { mutableStateOf(false) }
    var wholeWord by rememberSaveable(bookId) { mutableStateOf(false) }
    var useRegex by rememberSaveable(bookId) { mutableStateOf(false) }
    var unicode by rememberSaveable(bookId) { mutableStateOf(true) }
    var normalization by remember(bookId) { mutableStateOf(ArabicSearchNormalization.NONE) }
    var searchJump by remember { mutableStateOf<SearchJump?>(null) }
    var jumpSequence by remember { mutableStateOf(0) }
    val editorListState = rememberLazyListState()

    LaunchedEffect(searchJump?.sequence) {
        val match = searchJump?.match ?: return@LaunchedEffect
        editorListState.animateScrollToItem(match.entryIndex + 1)
    }

    val searchOptions = remember(caseSensitive, wholeWord, useRegex, unicode, normalization) {
        BookEditorSearchOptions(
            caseSensitive = caseSensitive,
            wholeWord = wholeWord,
            regex = useRegex,
            unicode = unicode,
            normalization = if (useRegex) ArabicSearchNormalization.NONE else normalization
        )
    }
    val searchResult = remember(state.text, searchQuery, searchOptions) {
        BookEditorSearch.find(state.text, searchQuery, searchOptions)
    }
    val matches = (searchResult as? BookEditorSearchResult.Matches)?.items.orEmpty()
    var selectedMatchKeys by remember(matches) {
        mutableStateOf(matches.mapTo(mutableSetOf()) { it.key })
    }

    fun requestBack() {
        if (state.isDirty) showDiscardDialog = true else onNavigateBack()
    }

    BackHandler(enabled = state.isDirty && !showSearch) {
        showDiscardDialog = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back_rounded_24px),
                            contentDescription = stringResource(R.string.go_back_content_desc)
                        )
                    }
                },
                title = {
                    Text(
                        text = state.book?.title ?: stringResource(R.string.book_editor_title),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            showSearchReturn = false
                            showSearch = true
                        },
                        enabled = !state.isLoading
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search_rounded_24px),
                            contentDescription = stringResource(R.string.book_editor_search)
                        )
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                val saved = model.save()
                                if (saved) {
                                    if (returnToReader) {
                                        ReaderScreen.reloadEditedBookChannel.trySend(bookId)
                                    }
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.book_editor_saved)
                                    )
                                } else {
                                    model.state.value.errorMessage?.let {
                                        context.getString(R.string.book_editor_save_error, it).showToast(context)
                                    }
                                }
                            }
                        },
                        enabled = state.isDirty && !state.isSaving
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check_rounded_24px),
                            contentDescription = stringResource(R.string.book_editor_save)
                        )
                    }
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert_rounded_24px),
                            contentDescription = stringResource(R.string.book_editor_more_options)
                        )
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.book_editor_restore_source)) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings_backup_restore_rounded_24px),
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                showRestoreDialog = true
                            }
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (!state.isLoading && state.book != null) {
                Surface(shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = model::undo,
                            enabled = state.undoStack.isNotEmpty()
                        ) {
                            Text(stringResource(R.string.book_editor_undo_count, state.undoStack.size))
                        }
                        TextButton(
                            onClick = model::redo,
                            enabled = state.redoStack.isNotEmpty()
                        ) {
                            Text(stringResource(R.string.book_editor_redo_count, state.redoStack.size))
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.isDirty) {
                            Text(
                                stringResource(R.string.book_editor_unsaved),
                                color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (showSearchReturn && !showSearch) {
                FilledTonalButton(
                    onClick = {
                        showSearchReturn = false
                        showSearch = true
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search_rounded_24px),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.book_editor_return_to_results))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            state.isLoading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                    Text(
                        stringResource(R.string.book_editor_loading),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }

            state.errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.book_editor_open_error, state.errorMessage),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    TextButton(onClick = onNavigateBack, modifier = Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.book_editor_back))
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    state = editorListState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "editor-note") {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = stringResource(R.string.book_editor_source_preserved),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    itemsIndexed(
                        items = state.text,
                        key = { index, _ -> "reader-entry-$index" }
                    ) { index, entry ->
                        when (entry) {
                            is ReaderText.Chapter -> EditorSourceField(
                                bookId = bookId,
                                entryIndex = index,
                                text = entry.title,
                                label = stringResource(R.string.book_editor_chapter_title),
                                minLines = 1,
                                maxLines = 2,
                                jump = searchJump,
                                onTextChange = { model.changeText(index, it) }
                            )

                            is ReaderText.Text -> EditorSourceField(
                                bookId = bookId,
                                entryIndex = index,
                                text = entry.source,
                                label = stringResource(R.string.book_editor_paragraph),
                                minLines = 3,
                                maxLines = Int.MAX_VALUE,
                                jump = searchJump,
                                onTextChange = { model.changeText(index, it) }
                            )

                            is ReaderText.Math -> EditorSourceField(
                                bookId = bookId,
                                entryIndex = index,
                                text = entry.latex,
                                label = stringResource(R.string.book_editor_math),
                                minLines = 2,
                                maxLines = Int.MAX_VALUE,
                                jump = searchJump,
                                onTextChange = { model.changeText(index, it) }
                            )

                            is ReaderText.Image -> PreservedEntry(
                                text = stringResource(R.string.book_editor_image_preserved)
                            )

                            ReaderText.Separator -> {
                                Column {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Text(
                                        text = stringResource(R.string.book_editor_separator_preserved),
                                        modifier = Modifier.padding(top = 6.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSearch) {
        ModalBottomSheet(
            onDismissRequest = { showSearch = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            SearchSheet(
                query = searchQuery,
                replacement = replacement,
                caseSensitive = caseSensitive,
                wholeWord = wholeWord,
                useRegex = useRegex,
                unicode = unicode,
                normalization = normalization,
                result = searchResult,
                matches = matches,
                selectedKeys = selectedMatchKeys,
                onQueryChange = { searchQuery = it },
                onReplacementChange = { replacement = it },
                onCaseSensitiveChange = { caseSensitive = it },
                onWholeWordChange = { wholeWord = it },
                onRegexChange = {
                    useRegex = it
                    if (it) normalization = ArabicSearchNormalization.NONE
                },
                onUnicodeChange = { unicode = it },
                onNormalizationCycle = {
                    normalization = when (normalization) {
                        ArabicSearchNormalization.NONE -> ArabicSearchNormalization.ALEF
                        ArabicSearchNormalization.ALEF -> ArabicSearchNormalization.BROAD
                        ArabicSearchNormalization.BROAD -> ArabicSearchNormalization.NONE
                    }
                },
                onSelectionChange = { match, selected ->
                    selectedMatchKeys = if (selected) selectedMatchKeys + match.key
                    else selectedMatchKeys - match.key
                },
                onJump = { match ->
                    jumpSequence += 1
                    searchJump = SearchJump(match, jumpSequence)
                    showSearch = false
                    showSearchReturn = true
                },
                onReplaceSelected = {
                    applyReplacement(
                        state.text,
                        matches,
                        selectedMatchKeys,
                        searchQuery,
                        replacement,
                        searchOptions,
                        model
                    )
                },
                onReplaceAll = {
                    applyReplacement(
                        state.text,
                        matches,
                        matches.mapTo(mutableSetOf()) { it.key },
                        searchQuery,
                        replacement,
                        searchOptions,
                        model
                    )
                }
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.book_editor_unsaved_title)) },
            text = { Text(stringResource(R.string.book_editor_unsaved_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        scope.launch {
                            if (model.save()) {
                                if (returnToReader) ReaderScreen.reloadEditedBookChannel.trySend(bookId)
                                onNavigateBack()
                            } else {
                                model.state.value.errorMessage?.let {
                                    context.getString(R.string.book_editor_save_error, it).showToast(context)
                                }
                            }
                        }
                    },
                    enabled = !state.isSaving
                ) {
                    Text(stringResource(R.string.book_editor_save_and_leave))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(onClick = {
                        showDiscardDialog = false
                        onNavigateBack()
                    }) {
                        Text(stringResource(R.string.book_editor_discard))
                    }
                }
            }
        )
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(stringResource(R.string.book_editor_restore_source)) },
            text = { Text(stringResource(R.string.book_editor_restore_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreDialog = false
                        scope.launch {
                            if (model.restoreOriginal()) {
                                if (returnToReader) ReaderScreen.reloadEditedBookChannel.trySend(bookId)
                                snackbarHostState.showSnackbar(context.getString(R.string.book_editor_restored))
                            } else {
                                model.state.value.errorMessage?.let {
                                    context.getString(R.string.book_editor_save_error, it).showToast(context)
                                }
                            }
                        }
                    },
                    enabled = !state.isSaving
                ) {
                    Text(stringResource(R.string.book_editor_restore_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun EditorSourceField(
    bookId: Int,
    entryIndex: Int,
    text: String,
    label: String,
    minLines: Int,
    maxLines: Int,
    jump: SearchJump?,
    onTextChange: (String) -> Unit
) {
    val focusRequester = remember(bookId, entryIndex) { FocusRequester() }
    var fieldValue by remember(bookId, entryIndex) { mutableStateOf(TextFieldValue(text)) }

    LaunchedEffect(text) {
        if (fieldValue.text != text) fieldValue = TextFieldValue(text)
    }
    LaunchedEffect(jump?.sequence) {
        val match = jump?.match ?: return@LaunchedEffect
        if (match.entryIndex != entryIndex) return@LaunchedEffect
        val start = match.start.coerceIn(0, text.length)
        val end = match.end.coerceIn(start, text.length)
        fieldValue = TextFieldValue(text, selection = TextRange(start, end))
        focusRequester.requestFocus()
    }

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { value ->
            fieldValue = value
            onTextChange(value.text)
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        label = { Text(label) },
        minLines = minLines,
        maxLines = maxLines,
        shape = MaterialTheme.shapes.medium,
        textStyle = MaterialTheme.typography.bodyLarge
    )
}

@Composable
private fun PreservedEntry(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SearchSheet(
    query: String,
    replacement: String,
    caseSensitive: Boolean,
    wholeWord: Boolean,
    useRegex: Boolean,
    unicode: Boolean,
    normalization: ArabicSearchNormalization,
    result: BookEditorSearchResult,
    matches: List<BookEditorMatch>,
    selectedKeys: Set<String>,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onCaseSensitiveChange: (Boolean) -> Unit,
    onWholeWordChange: (Boolean) -> Unit,
    onRegexChange: (Boolean) -> Unit,
    onUnicodeChange: (Boolean) -> Unit,
    onNormalizationCycle: () -> Unit,
    onSelectionChange: (BookEditorMatch, Boolean) -> Unit,
    onJump: (BookEditorMatch) -> Unit,
    onReplaceSelected: () -> Unit,
    onReplaceAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.book_editor_search),
            style = MaterialTheme.typography.titleLarge
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.book_editor_search_for)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium
        )
        OutlinedTextField(
            value = replacement,
            onValueChange = onReplacementChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.book_editor_replace_with)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchOptionChip(
                label = stringResource(R.string.book_editor_case_sensitive),
                selected = caseSensitive,
                onClick = { onCaseSensitiveChange(!caseSensitive) }
            )
            SearchOptionChip(
                label = stringResource(R.string.book_editor_whole_word),
                selected = wholeWord,
                onClick = { onWholeWordChange(!wholeWord) }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchOptionChip(
                label = stringResource(R.string.book_editor_regex),
                selected = useRegex,
                onClick = { onRegexChange(!useRegex) }
            )
            SearchOptionChip(
                label = stringResource(R.string.book_editor_unicode),
                selected = unicode,
                onClick = { onUnicodeChange(!unicode) }
            )
        }
        OutlinedButton(
            onClick = onNormalizationCycle,
            enabled = !useRegex,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(
                    when (normalization) {
                        ArabicSearchNormalization.NONE -> R.string.book_editor_normalize_none
                        ArabicSearchNormalization.ALEF -> R.string.book_editor_normalize_alef
                        ArabicSearchNormalization.BROAD -> R.string.book_editor_normalize_arabic
                    }
                )
            )
        }

        when (result) {
            BookEditorSearchResult.InvalidPattern -> Text(
                stringResource(R.string.book_editor_invalid_regex),
                color = MaterialTheme.colorScheme.error
            )

            is BookEditorSearchResult.Matches -> {
                Text(
                    text = if (result.capped) {
                        stringResource(R.string.book_editor_results_capped, matches.size)
                    } else {
                        stringResource(R.string.book_editor_results_count, matches.size)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (matches.isEmpty()) {
                    Text(
                        stringResource(R.string.book_editor_no_results),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(
                            items = matches,
                            key = { _, match -> match.key }
                        ) { _, match ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = match.key in selectedKeys,
                                    onCheckedChange = { onSelectionChange(match, it) }
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "${match.before}${match.matchedText}${match.after}",
                                        maxLines = 2,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = match.matchedText,
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                IconButton(onClick = { onJump(match) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_visibility_rounded_24px),
                                        contentDescription = stringResource(R.string.book_editor_eye_jump)
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            TextButton(
                onClick = onReplaceSelected,
                enabled = selectedKeys.isNotEmpty()
            ) {
                Text(stringResource(R.string.book_editor_replace_selected, selectedKeys.size))
            }
            Button(
                onClick = onReplaceAll,
                enabled = matches.isNotEmpty()
            ) {
                Text(stringResource(R.string.book_editor_replace_all))
            }
        }
        Spacer(Modifier.heightIn(min = 12.dp))
    }
}

@Composable
private fun SearchOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

private fun applyReplacement(
    sourceText: List<ReaderText>,
    matches: List<BookEditorMatch>,
    selectedKeys: Set<String>,
    query: String,
    replacement: String,
    options: BookEditorSearchOptions,
    model: BookEditorModel
) {
    if (matches.isEmpty() || selectedKeys.isEmpty()) return
    val updated = sourceText.toMutableList()
    matches
        .filter { it.key in selectedKeys }
        .groupBy { it.entryIndex }
        .forEach { (index, group) ->
            val source = updated.getOrNull(index)?.editorSource() ?: return@forEach
            val replaced = BookEditorSearch.replace(
                source = source,
                matches = group,
                selectedKeys = selectedKeys,
                replacement = replacement,
                options = options,
                query = query
            )
            if (replaced != source) updated[index] = updated[index].withEditorSource(replaced)
        }
    model.applyBatch(updated)
}
