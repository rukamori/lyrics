/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import moe.rukamori.archivetune.musixmatch.models.RichSyncLine
import moe.rukamori.archivetune.musixmatch.models.RichSyncWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusixmatchTtmlTest {

    @Test
    fun `escapeXml handles special characters correctly`() {
        assertEquals("&amp;", MusixmatchTtml.escapeXml("&"))
        assertEquals("&lt;", MusixmatchTtml.escapeXml("<"))
        assertEquals("&gt;", MusixmatchTtml.escapeXml(">"))
        assertEquals("&quot;", MusixmatchTtml.escapeXml("\""))
        assertEquals("&apos;", MusixmatchTtml.escapeXml("'"))
        assertEquals("Rock &amp; Roll &lt;Live&gt;", MusixmatchTtml.escapeXml("Rock & Roll <Live>"))
    }

    @Test
    fun `formatTtmlTime formats seconds to 3 decimal places`() {
        assertEquals("1.234s", MusixmatchTtml.formatTtmlTime(1.234))
        assertEquals("0.050s", MusixmatchTtml.formatTtmlTime(0.05))
        assertEquals("12.000s", MusixmatchTtml.formatTtmlTime(12.0))
    }

    @Test
    fun `convertLine returns null for empty line`() {
        val emptyLine = RichSyncLine(ts = 1.0, te = 2.0, l = emptyList(), x = null)
        assertNull(MusixmatchTtml.convertLine(emptyLine))
    }

    @Test
    fun `convertLine returns null when end time is before or equal start time`() {
        val invalidLine = RichSyncLine(
            ts = 5.0,
            te = 5.0,
            l = listOf(RichSyncWord(c = "Hello", o = 0.0)),
            x = "Hello",
        )
        assertNull(MusixmatchTtml.convertLine(invalidLine))
    }

    @Test
    fun `convertLine handles word sync spans and gaps`() {
        val line = RichSyncLine(
            ts = 1.0,
            te = 4.0,
            l = listOf(
                RichSyncWord(c = "Hello", o = 0.0),
                RichSyncWord(c = " ", o = 0.5),
                RichSyncWord(c = "World", o = 1.0),
            ),
            x = "Hello World",
        )

        val result = MusixmatchTtml.convertLine(line)
        assertNotNull(result)
        assertTrue(result!!.startsWith("<p begin=\"1.000s\" end=\"4.000s\">"))
        assertTrue(result.contains("<span begin=\"1.000s\" end=\"2.000s\">Hello</span> <span begin=\"2.000s\" end=\"4.000s\">World</span>"))
    }

    @Test
    fun `convertRichSyncToTtml builds valid TTML document`() {
        val lines = listOf(
            RichSyncLine(
                ts = 1.0,
                te = 3.0,
                l = listOf(RichSyncWord(c = "First", o = 0.0)),
                x = "First",
            ),
            RichSyncLine(
                ts = 3.5,
                te = 5.0,
                l = listOf(RichSyncWord(c = "Second", o = 0.0)),
                x = "Second",
            ),
        )

        val ttml = MusixmatchTtml.convertRichSyncToTtml(lines)
        assertTrue(ttml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(ttml.contains("<tt xmlns=\"http://www.w3.org/ns/ttml\""))
        assertTrue(ttml.contains("<span begin=\"1.000s\" end=\"3.000s\">First</span>"))
        assertTrue(ttml.contains("<span begin=\"3.500s\" end=\"5.000s\">Second</span>"))
        assertTrue(ttml.endsWith("</tt>"))
    }

    @Test
    fun `convertRichSyncToTtml returns empty string when all lines invalid`() {
        val lines = listOf(
            RichSyncLine(ts = 1.0, te = 1.0, l = emptyList(), x = null),
        )
        val ttml = MusixmatchTtml.convertRichSyncToTtml(lines)
        assertEquals("", ttml)
    }
}
