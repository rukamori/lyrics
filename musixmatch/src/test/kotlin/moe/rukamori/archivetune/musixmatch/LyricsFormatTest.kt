/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val validRichSync = """[{"ts":1.0,"te":2.0,"l":[{"c":"Hello","o":0.0},{"c":"World","o":0.5}],"x":"Hello World"}]"""
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
    fun tryTtml_lineLevelRichSync_returnsNull() {
        val lineLevelRichSync = """[{"ts":1.0,"te":2.0,"l":[{"c":"Hello","o":0.0}],"x":"Hello"}]"""
        assertNull(format.tryTtml(lineLevelRichSync))
    }

    @Test
    fun parseLrc_multipleTimestampsOnSameLine_parsesAndSortsAllTimestamps() {
        val rawLrc = "[00:05.00][00:01.00] Repeated chorus\n[00:03.00] Middle line"
        val parsed = format.parseLrc(rawLrc)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.size)
        assertEquals(1.0, parsed[0].seconds, 0.001)
        assertEquals("Repeated chorus", parsed[0].text)
        assertEquals(3.0, parsed[1].seconds, 0.001)
        assertEquals("Middle line", parsed[1].text)
        assertEquals(5.0, parsed[2].seconds, 0.001)
        assertEquals("Repeated chorus", parsed[2].text)
    }

    @Test
    fun tryPlain_cleansCommercialLicenseFooters() {
        val raw = "Line 1\nThis lyrics is not for commercial use\nLine 2"
        val result = format.tryPlain(raw)
        assertEquals("Line 1\nLine 2", result)
    }

    @Test
    fun formatRichSync_wordSyncTrue_returnsTtmlQuality2() {
        val richSync = """[
            {"ts":1.0,"te":3.0,"l":[{"c":"Hello","o":0.0},{"c":"world","o":0.5}],"x":"Hello world"},
            {"ts":4.0,"te":6.0,"l":[{"c":"Foo","o":0.0},{"c":"bar","o":0.8}],"x":"Foo bar"}
        ]"""
        val result = format.formatRichSync(richSync, subtitleBody = null)
        assertNotNull(result)
        assertEquals(2, result!!.quality)
        assertTrue(result.content.contains("<tt"))
        assertTrue(result.content.contains("<span"))
    }

    @Test
    fun formatRichSync_wordSyncFalse_equivalentLrc_returnsOfficialLrcQuality1() {
        val richSync = """[
            {"ts":1.0,"te":3.0,"l":[{"c":"Hello world","o":0.0}],"x":"Hello world"},
            {"ts":4.0,"te":6.0,"l":[{"c":"Foo bar","o":0.0}],"x":"Foo bar"}
        ]"""
        val lrc = "[00:01.00] Hello world\n[00:04.00] Foo bar"
        val result = format.formatRichSync(richSync, lrc)
        assertNotNull(result)
        assertEquals(1, result!!.quality)
        assertEquals(lrc, result.content)
    }

    @Test
    fun formatRichSync_wordSyncFalse_absentLrc_returnsSynthesizedLrcQuality1() {
        val richSync = """[
            {"ts":1.25,"te":3.0,"l":[{"c":"Hello world","o":0.0}],"x":"Hello world"}
        ]"""
        val result = format.formatRichSync(richSync, subtitleBody = null)
        assertNotNull(result)
        assertEquals(1, result!!.quality)
        assertEquals("[00:01.250] Hello world", result.content)
    }

    @Test
    fun formatRichSync_wordSyncFalse_mismatchedTimestamps_returnsSynthesizedLrcQuality1() {
        val richSync = """[
            {"ts":1.0,"te":3.0,"l":[{"c":"Hello world","o":0.0}],"x":"Hello world"}
        ]"""
        val lrc = "[00:01.50] Hello world"
        val result = format.formatRichSync(richSync, lrc)
        assertNotNull(result)
        assertEquals(1, result!!.quality)
        assertEquals("[00:01.000] Hello world", result.content)
    }

    @Test
    fun formatRichSync_mixedPayload42Percent_returnsLrcQuality1() {
        val richSync = """[
            {"ts":1.0,"te":2.0,"l":[{"c":"A","o":0.0},{"c":"B","o":0.5}],"x":"A B"},
            {"ts":2.0,"te":3.0,"l":[{"c":"C","o":0.0},{"c":"D","o":0.5}],"x":"C D"},
            {"ts":3.0,"te":4.0,"l":[{"c":"E","o":0.0}],"x":"E"},
            {"ts":4.0,"te":5.0,"l":[{"c":"F","o":0.0}],"x":"F"},
            {"ts":5.0,"te":6.0,"l":[{"c":"G","o":0.0}],"x":"G"}
        ]"""
        val analysis = format.analyzeRichSync(json.decodeFromString(richSync))
        assertEquals(5, analysis.nonEmptyLines)
        assertEquals(2, analysis.timedLines)
        assertEquals(0.4, analysis.timedRatio, 0.001)
        assertFalse(analysis.wordSync)

        val result = format.formatRichSync(richSync, subtitleBody = null)
        assertNotNull(result)
        assertEquals(1, result!!.quality)
        assertFalse(result.content.contains("<tt"))
    }

    @Test
    fun formatRichSync_mixedPayload58Percent_returnsTtmlQuality2() {
        val richSync = """[
            {"ts":1.0,"te":2.0,"l":[{"c":"A","o":0.0},{"c":"B","o":0.5}],"x":"A B"},
            {"ts":2.0,"te":3.0,"l":[{"c":"C","o":0.0},{"c":"D","o":0.5}],"x":"C D"},
            {"ts":3.0,"te":4.0,"l":[{"c":"E","o":0.0},{"c":"F","o":0.5}],"x":"E F"},
            {"ts":4.0,"te":5.0,"l":[{"c":"G","o":0.0}],"x":"G"},
            {"ts":5.0,"te":6.0,"l":[{"c":"H","o":0.0}],"x":"H"}
        ]"""
        val analysis = format.analyzeRichSync(json.decodeFromString(richSync))
        assertEquals(5, analysis.nonEmptyLines)
        assertEquals(3, analysis.timedLines)
        assertEquals(0.6, analysis.timedRatio, 0.001)
        assertTrue(analysis.wordSync)

        val result = format.formatRichSync(richSync, subtitleBody = null)
        assertNotNull(result)
        assertEquals(2, result!!.quality)
        assertTrue(result.content.contains("<tt"))
    }

    @Test
    fun formatRichSync_oneLineLineLevelPayload_returnsLrcQuality1() {
        val richSync = """[{"ts":1.0,"te":3.0,"l":[{"c":"One token line","o":0.0}],"x":"One token line"}]"""
        val result = format.formatRichSync(richSync, subtitleBody = null)
        assertNotNull(result)
        assertEquals(1, result!!.quality)
        assertEquals("[00:01.000] One token line", result.content)
    }

    @Test
    fun formatRichSync_oneLineMultipleTokenTimedPayload_returnsTtmlQuality2() {
        val richSync = """[{"ts":1.0,"te":3.0,"l":[{"c":"One","o":0.0},{"c":"Two","o":0.5}],"x":"One Two"}]"""
        val result = format.formatRichSync(richSync, subtitleBody = null)
        assertNotNull(result)
        assertEquals(2, result!!.quality)
        assertTrue(result.content.contains("<tt"))
    }

    @Test
    fun formatRichSync_lrcWithExtraEmptyGapLines_stillComparesAsEquivalent() {
        val richSync = """[
            {"ts":1.0,"te":2.0,"l":[{"c":"First","o":0.0}],"x":"First"},
            {"ts":5.0,"te":6.0,"l":[{"c":"Second","o":0.0}],"x":"Second"}
        ]"""
        val lrc = "[00:01.00] First\n[00:02.50] \n[00:03.00] ♪\n[00:05.00] Second"
        val result = format.formatRichSync(richSync, lrc)
        assertNotNull(result)
        assertEquals(1, result!!.quality)
        assertEquals(lrc, result.content)
    }

    @Test
    fun formatRichSync_malformedRichSync_returnsNull() {
        assertNull(format.formatRichSync(null, "[00:01.00] Hello"))
        assertNull(format.formatRichSync("not json", "[00:01.00] Hello"))
        assertNull(format.formatRichSync("[]", "[00:01.00] Hello"))
    }
}
