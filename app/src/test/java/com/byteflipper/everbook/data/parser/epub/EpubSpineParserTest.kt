/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.data.parser.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubSpineParserTest {
    @Test
    fun preservesSpineOrderAndMarksLinearNoWithoutDroppingItsNavigationTarget() {
        val opf = """
            <package>
              <manifest>
                <item id="before" href="chapter_47.xhtml" />
                <item id="paper-toc" href="chapter_48.xhtml" />
                <item id="after" href="chapter_3.xhtml" />
              </manifest>
              <spine>
                <itemref idref="before" />
                <itemref idref="paper-toc" linear="no" />
                <itemref idref="after" linear="yes" />
              </spine>
            </package>
        """.trimIndent()

        val result = EpubSpineParser.parse(opf, "OEBPS/content.opf")

        assertEquals(
            listOf(
                EpubSpineItem("OEBPS/chapter_47.xhtml", true),
                EpubSpineItem("OEBPS/chapter_48.xhtml", false),
                EpubSpineItem("OEBPS/chapter_3.xhtml", true)
            ),
            result
        )
        assertTrue(result.any { it.archivePath.endsWith("chapter_48.xhtml") && !it.isLinear })
    }
}
