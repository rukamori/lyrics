/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFormatTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val format = LyricsFormat(json)

    @Test
    fun tryLrc_validJsonFormattedSubtitles_returnsTimestampedLrc() {
        val jsonInput = """[{"text":"Line 1","time":{"total":1.25}},{"text":"Line 2","time":{"total":65.5}}]"""
        val result = format.tryLrc(jsonInput)
        assertNotNull(result)
        assertTrue(result!!.contains("[00:01.250] Line 1"))
        assertTrue(result.contains("[01:05.500] Line 2"))
    }

    @Test
    fun tryLrc_validRawLrcWithTimestamps_returnsTrimmedContent() {
        val rawLrc = "[00:04.12] First line\n[00:10.50] Second line"
        val result = format.tryLrc(rawLrc)
        assertEquals(rawLrc, result)
    }

    @Test
    fun tryLrc_brokenJsonArray_returnsNull() {
        val brokenJson = """[{"text": "broken json without closing"""
        val result = format.tryLrc(brokenJson)
        assertNull(result)
    }

    @Test
    fun tryLrc_textWithSectionHeader_returnsNull() {
        val sectionHeader = "[Verse 1]\nThis is plain text with no timestamps"
        val result = format.tryLrc(sectionHeader)
        assertNull(result)
    }

    @Test
    fun tryLrc_blankOrNull_returnsNull() {
        assertNull(format.tryLrc(null))
        assertNull(format.tryLrc(""))
        assertNull(format.tryLrc("   "))
    }

    @Test
    fun tryTtml_validRichSync_returnsXml() {
        val validRichSync = """[{"ts":1.0,"te":2.0,"l":[{"c":"Hello","o":0}]}]"""
        val result = format.tryTtml(validRichSync)
        assertNotNull(result)
        assertTrue(result!!.contains("<tt"))
        assertTrue(result.contains("Hello"))
    }

    @Test
    fun tryTtml_invalidOrNull_returnsNull() {
        assertNull(format.tryTtml(null))
        assertNull(format.tryTtml("not json"))
        assertNull(format.tryTtml("[]"))
    }

    @Test
    fun tryPlain_cleansCommercialLicenseFooters() {
        val raw = "Line 1\nThis lyrics is not for commercial use\nLine 2"
        val result = format.tryPlain(raw)
        assertEquals("Line 1\nLine 2", result)
    }
}
