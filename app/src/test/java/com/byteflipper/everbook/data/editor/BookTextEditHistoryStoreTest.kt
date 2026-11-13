/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.data.editor

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookTextEditHistoryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val source = BookTextEditSourceIdentity(
        path = "/books/sample.epub",
        size = 1024L,
        lastModified = 1234L
    )

    @Test
    fun snapshotsCanBeRestoredAndSameDayCheckpointTracksLatestSavedVersion() {
        val store = BookTextEditHistoryStore(temporaryFolder.root)
        val day = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val original = listOf(BookTextEditEntry(0, "text", "original"))
        val morning = listOf(BookTextEditEntry(0, "text", "morning"))
        val afternoon = listOf(BookTextEditEntry(0, "text", "afternoon"))

        store.addRevision(7, source, original, day, isOriginal = true)
        val firstDaily = store.addRevision(7, source, morning, day, isOriginal = false)
            .first { !it.isOriginal }
        val refreshedDaily = store.addRevision(
            7,
            source,
            afternoon,
            day + 60 * 60 * 1000,
            isOriginal = false
        )

        assertEquals(2, refreshedDaily.size)
        assertEquals(firstDaily.id, refreshedDaily.first { !it.isOriginal }.id)
        assertEquals(afternoon, store.load(7, source, firstDaily.id))
        assertEquals(original, store.load(7, source, "original"))
    }

    @Test
    fun beforeRestoreSnapshotSurvivesReplacingSameDayCheckpoint() {
        val store = BookTextEditHistoryStore(temporaryFolder.root)
        val now = System.currentTimeMillis()
        val original = listOf(BookTextEditEntry(0, "text", "original"))
        val current = listOf(BookTextEditEntry(0, "text", "current saved version"))
        val restored = listOf(BookTextEditEntry(0, "text", "restored older version"))

        store.addRevision(7, source, original, now - 86_400_000L, isOriginal = true)
        store.addRevision(7, source, current, now, isOriginal = false)
        val beforeRestore = store.addRevision(
            7,
            source,
            current,
            now,
            isOriginal = false,
            label = "before_restore"
        ).first { it.label == "before_restore" }
        store.addRevision(7, source, restored, now + 1, isOriginal = false)

        val revisions = store.list(7, source)
        assertEquals(3, revisions.size)
        assertEquals(current, store.load(7, source, beforeRestore.id))
        assertEquals(restored, store.load(7, source, revisions.first { it.label == null }.id))
    }

    @Test
    fun revisionsAreScopedToTheExactSourceFingerprint() {
        val store = BookTextEditHistoryStore(temporaryFolder.root)
        val entries = listOf(BookTextEditEntry(0, "text", "saved"))
        store.addRevision(7, source, entries, 1_735_000_000_000L, isOriginal = true)

        val changedSource = source.copy(size = source.size + 1)
        assertTrue(store.list(7, changedSource).isEmpty())
        assertNull(store.load(7, changedSource, "original"))
        assertNotNull(store.load(7, source, "original"))
    }

    @Test
    fun deleteRemovesSnapshotsForTheBook() {
        val store = BookTextEditHistoryStore(temporaryFolder.root)
        store.addRevision(
            7,
            source,
            listOf(BookTextEditEntry(0, "text", "saved")),
            1_735_000_000_000L,
            isOriginal = true
        )

        store.delete(7)

        assertTrue(store.list(7, source).isEmpty())
        assertNull(store.load(7, source, "original"))
        assertFalse(temporaryFolder.root.resolve("book_7").exists())
    }
}
