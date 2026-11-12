/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.ui.book_editor

import com.byteflipper.everbook.domain.reader.ReaderText
import java.text.Normalizer
import java.util.regex.Matcher
import java.util.regex.Pattern

internal enum class ArabicSearchNormalization {
    NONE,
    ALEF,
    BROAD
}

internal data class BookEditorSearchOptions(
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
    val unicode: Boolean = true,
    val normalization: ArabicSearchNormalization = ArabicSearchNormalization.NONE
)

internal data class BookEditorMatch(
    val entryIndex: Int,
    val start: Int,
    val end: Int,
    val matchedText: String,
    val before: String,
    val after: String
) {
    val key: String get() = "$entryIndex:$start:$end"
}

internal sealed interface BookEditorSearchResult {
    data class Matches(val items: List<BookEditorMatch>, val capped: Boolean = false) : BookEditorSearchResult
    data object InvalidPattern : BookEditorSearchResult
}

internal fun ReaderText.editorSource(): String? = when (this) {
    is ReaderText.Chapter -> title
    is ReaderText.Text -> source
    is ReaderText.Math -> latex
    is ReaderText.Image, ReaderText.Separator -> null
}

internal fun ReaderText.withEditorSource(value: String): ReaderText = when (this) {
    is ReaderText.Chapter -> copy(title = value)
    is ReaderText.Text -> copy(source = value)
    is ReaderText.Math -> copy(latex = value)
    is ReaderText.Image, ReaderText.Separator -> this
}

internal object BookEditorSearch {
    const val MAX_RESULTS = 3000

    fun find(
        entries: List<ReaderText>,
        query: String,
        options: BookEditorSearchOptions,
        maxResults: Int = MAX_RESULTS
    ): BookEditorSearchResult {
        if (query.isEmpty()) return BookEditorSearchResult.Matches(emptyList())
        val pattern = try {
            buildPattern(query, options)
        } catch (_: IllegalArgumentException) {
            return BookEditorSearchResult.InvalidPattern
        }

        val output = mutableListOf<BookEditorMatch>()
        var capped = false
        for ((entryIndex, entry) in entries.withIndex()) {
            val source = entry.editorSource() ?: continue
            val prepared = if (!options.regex) normalizeForSearch(source, options.normalization) else null
            val candidate = prepared?.text ?: source
            val matcher = pattern.matcher(candidate)
            while (matcher.find()) {
                val range = if (prepared == null) {
                    matcher.start() to matcher.end()
                } else {
                    prepared.sourceRange(matcher.start(), matcher.end()) ?: continue
                }
                val (start, end) = range
                if (options.wholeWord && !isWholeWord(source, start, end)) continue
                output += BookEditorMatch(
                    entryIndex = entryIndex,
                    start = start,
                    end = end,
                    matchedText = source.substring(start, end),
                    before = source.substring((start - CONTEXT_LENGTH).coerceAtLeast(0), start),
                    after = source.substring(end, (end + CONTEXT_LENGTH).coerceAtMost(source.length))
                )
                if (output.size >= maxResults) {
                    capped = true
                    return BookEditorSearchResult.Matches(output, capped)
                }
                // Java regex does advance after a zero-width match, but do so explicitly on
                // older Android implementations to avoid a repeated empty result.
                if (matcher.start() == matcher.end() && matcher.end() == candidate.length) break
            }
        }
        return BookEditorSearchResult.Matches(output, capped)
    }

    fun replace(
        source: String,
        matches: List<BookEditorMatch>,
        selectedKeys: Set<String>,
        replacement: String,
        options: BookEditorSearchOptions,
        query: String
    ): String {
        val selected = matches
            .asSequence()
            .filter { it.key in selectedKeys }
            .sortedBy { it.start }
            .toList()
        if (selected.isEmpty()) return source

        if (options.regex && options.normalization == ArabicSearchNormalization.NONE) {
            val pattern = buildPattern(query, options)
            val matcher = pattern.matcher(source)
            val selectedRanges = selected.mapTo(mutableSetOf()) { it.start to it.end }
            val output = StringBuffer()
            while (matcher.find()) {
                val range = matcher.start() to matcher.end()
                val shouldReplace = range in selectedRanges &&
                    (!options.wholeWord || isWholeWord(source, matcher.start(), matcher.end()))
                matcher.appendReplacement(
                    output,
                    if (shouldReplace) replacement else Matcher.quoteReplacement(matcher.group())
                )
            }
            matcher.appendTail(output)
            return output.toString()
        }

        val output = StringBuilder(source)
        selected.asReversed().forEach { match ->
            output.replace(match.start, match.end, replacement)
        }
        return output.toString()
    }

    private fun buildPattern(query: String, options: BookEditorSearchOptions): Pattern {
        val expression = if (options.regex) {
            query
        } else {
            val normalized = normalizeForSearch(query, options.normalization).text
            Pattern.quote(normalized)
        }
        var flags = 0
        if (!options.caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE
        if (options.unicode) {
            flags = flags or Pattern.UNICODE_CASE or Pattern.UNICODE_CHARACTER_CLASS
        }
        return Pattern.compile(expression, flags)
    }

    private data class PreparedText(
        val text: String,
        val sourceStarts: IntArray,
        val sourceEnds: IntArray
    ) {
        fun sourceRange(start: Int, end: Int): Pair<Int, Int>? {
            if (start < 0 || end <= start || end > sourceStarts.size) return null
            return sourceStarts[start] to sourceEnds[end - 1]
        }
    }

    private fun normalizeForSearch(source: String, mode: ArabicSearchNormalization): PreparedText {
        if (mode == ArabicSearchNormalization.NONE) {
            return PreparedText(
                text = source,
                sourceStarts = IntArray(source.length) { it },
                sourceEnds = IntArray(source.length) { it + 1 }
            )
        }

        val normalized = StringBuilder()
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        var sourceOffset = 0
        while (sourceOffset < source.length) {
            val codePoint = source.codePointAt(sourceOffset)
            val sourceEnd = sourceOffset + Character.charCount(codePoint)
            val transformed = normalizeCodePoint(codePoint, mode)
            transformed.forEach { char ->
                normalized.append(char)
                starts += sourceOffset
                ends += sourceEnd
            }
            sourceOffset = sourceEnd
        }
        return PreparedText(normalized.toString(), starts.toIntArray(), ends.toIntArray())
    }

    private fun normalizeCodePoint(codePoint: Int, mode: ArabicSearchNormalization): String {
        var value = String(Character.toChars(codePoint))
        value = Normalizer.normalize(value, Normalizer.Form.NFC)
        if (mode == ArabicSearchNormalization.BROAD) {
            val filtered = StringBuilder()
            value.codePoints().forEach { cp ->
                val type = Character.getType(cp)
                if (cp == 0x0640 || type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() ||
                    type == Character.ENCLOSING_MARK.toInt()
                ) return@forEach
                filtered.appendCodePoint(cp)
            }
            value = filtered.toString()
        }
        if (value.isEmpty()) return value

        val cp = value.codePointAt(0)
        return when (cp) {
            0x0622, 0x0623, 0x0625, 0x0671 -> "ا"
            0x0649 -> if (mode == ArabicSearchNormalization.BROAD) "ي" else value
            0x0624 -> if (mode == ArabicSearchNormalization.BROAD) "و" else value
            0x0626 -> if (mode == ArabicSearchNormalization.BROAD) "ي" else value
            else -> value
        }
    }

    private fun isWholeWord(source: String, start: Int, end: Int): Boolean {
        val beforeIsWord = start > 0 && isWordCodePoint(source.codePointBefore(start))
        val afterIsWord = end < source.length && isWordCodePoint(source.codePointAt(end))
        return !beforeIsWord && !afterIsWord
    }

    private fun isWordCodePoint(codePoint: Int): Boolean {
        return Character.isLetterOrDigit(codePoint) ||
            Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(codePoint) == Character.COMBINING_SPACING_MARK.toInt() ||
            codePoint == '_'.code
    }

    private const val CONTEXT_LENGTH = 24
}
