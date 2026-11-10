/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFormatTest {

    private val format = LyricsFormat()

    @Test
    fun `tryTtml decodes valid JSON richsync into TTML`() {
        val richsyncJson = """
            [
                {
                    "ts": 1.5,
                    "te": 3.0,
                    "l": [{"c": "Hello", "o": 0.0}],
                    "x": "Hello"
                }
            ]
        """.trimIndent()

        val ttml = format.tryTtml(richsyncJson)
        assertNotNull(ttml)
        assertTrue(ttml!!.contains("<span begin=\"1.500s\" end=\"3.000s\">Hello</span>"))
    }

    @Test
    fun `tryTtml returns null on empty or invalid json`() {
        assertNull(format.tryTtml(null))
        assertNull(format.tryTtml(""))
        assertNull(format.tryTtml("invalid json"))
        assertNull(format.tryTtml("[]"))
    }

    @Test
    fun `tryLrc decodes JSON subtitle lines to standard LRC`() {
        val subtitleJson = """
            [
                {"text": "Line one", "time": {"total": 65.432}},
                {"text": "Line two", "time": {"total": 120.0}}
            ]
        """.trimIndent()

        val lrc = format.tryLrc(subtitleJson)
        assertNotNull(lrc)
        val lines = lrc!!.lines()
        assertEquals(2, lines.size)
        assertEquals("[01:05.432] Line one", lines[0])
        assertEquals("[02:00.000] Line two", lines[1])
    }

    @Test
    fun `tryLrc accepts raw LRC format directly`() {
        val rawLrc = "[00:15.500] Raw lyrics line\n[00:20.000] Second line"
        val lrc = format.tryLrc(rawLrc)
        assertEquals(rawLrc, lrc)
    }

    @Test
    fun `tryLrc replaces blank line text with musical note`() {
        val subtitleJson = """
            [
                {"text": "   ", "time": {"total": 10.0}}
            ]
        """.trimIndent()

        val lrc = format.tryLrc(subtitleJson)
        assertEquals("[00:10.000] ♪", lrc)
    }

    @Test
    fun `tryPlain strips license footer notices`() {
        val rawLyrics = """
            Some lyrics text
            Another line
            
            ******* This lyrics is NOT for Commercial use *******
        """.trimIndent()

        val clean = format.tryPlain(rawLyrics)
        assertNotNull(clean)
        assertEquals("Some lyrics text\nAnother line", clean)
    }

    @Test
    fun `tryPlain returns null when all lines are license footer or empty`() {
        val onlyFooter = "******* This lyrics is NOT for Commercial use *******"
        assertNull(format.tryPlain(onlyFooter))
        assertNull(format.tryPlain(""))
        assertNull(format.tryPlain(null))
    }
}
