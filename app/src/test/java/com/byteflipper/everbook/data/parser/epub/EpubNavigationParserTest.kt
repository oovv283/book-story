package com.byteflipper.everbook.data.parser.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubNavigationParserTest {
    @Test
    fun parsesArabicNavigationHierarchyAndResolvesFragmentTargets() {
        val nav = """
            <html xmlns="http://www.w3.org/1999/xhtml"
                  xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc">
                  <ol>
                    <li><a href="chapter_48.xhtml">فهرس الطبعة الورقية</a></li>
                    <li>
                      <a href="chapter_8.xhtml">الجزء الأول: الناجي من هاتسين</a>
                      <ol>
                        <li><a href="chapter_9.xhtml#chapter-heading">الفصل الأول</a></li>
                      </ol>
                    </li>
                  </ol>
                </nav>
              </body>
            </html>
        """.trimIndent()

        val entries = EpubNavigationParser.parse(nav, "OEBPS/nav.xhtml")

        assertEquals(3, entries.size)
        assertEquals("OEBPS/chapter_48.xhtml", entries[0].sourcePath)
        assertEquals("فهرس الطبعة الورقية", entries[0].title)
        assertFalse(entries[0].nested)
        assertEquals("الجزء الأول: الناجي من هاتسين", entries[1].title)
        assertFalse(entries[1].nested)
        assertEquals("OEBPS/chapter_9.xhtml", entries[2].sourcePath)
        assertEquals("الفصل الأول", entries[2].title)
        assertTrue(entries[2].nested)
    }

    @Test
    fun resolvesParentDirectoriesAndPercentEncodedArchiveNames() {
        val href = "../Text%20Files/chapter%201.xhtml?mode=read#heading"

        assertEquals(
            "Text Files/chapter 1.xhtml",
            EpubNavigationParser.resolveArchivePath("OEBPS/nav.xhtml", href)
        )
    }

    @Test
    fun ignoresExternalTargetsAndDocumentsWithoutAnEpubToc() {
        val nav = """
            <html xmlns="http://www.w3.org/1999/xhtml"
                  xmlns:epub="http://www.idpf.org/2007/ops">
              <body><nav epub:type="landmarks"><ol>
                <li><a href="https://example.invalid/book">External</a></li>
              </ol></nav></body>
            </html>
        """.trimIndent()

        assertTrue(EpubNavigationParser.parse(nav, "OEBPS/nav.xhtml").isEmpty())
        assertEquals(
            null,
            EpubNavigationParser.resolveArchivePath("OEBPS/nav.xhtml", "https://example.invalid/book")
        )
    }
}
