/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import moe.rukamori.archivetune.betterlyrics.TTMLParser
import moe.rukamori.archivetune.musixmatch.models.RichSyncLine
import moe.rukamori.archivetune.musixmatch.models.RichSyncWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusixmatchTtmlRoundTripTest {
    @Test
    fun parserPreservesEscapedTextAndWordTiming() {
        val expected = listOf("Don't lose <3 & hope", "We stay together")
        val lines = expected.mapIndexed { index, text ->
            val start = 1.0 + index * 10.0
            val words = Regex("[^ ]+| +").findAll(text).mapIndexed { offset, match ->
                RichSyncWord(text = match.value, offset = offset * 0.25)
            }.toList()
            RichSyncLine(startTime = start, endTime = start + 5.0, words = words, text = text)
        }
        val ttml = requireNotNull(MusixmatchTtml.convertRichSyncToTtml(lines))
        val parsed = TTMLParser.parseTTML(ttml)
        assertEquals(expected, parsed.map { it.text })
        for ((index, line) in parsed.withIndex()) {
            assertEquals(lines[index].startTime, line.startTime, 0.001)
            assertEquals(lines[index].endTime, line.endTime, 0.001)
            assertEquals(expected[index], line.words.joinToString("") { it.text })
            assertTrue(line.words.all { it.endTime > it.startTime })
            assertTrue(line.words.zipWithNext().all { (a, b) -> a.startTime <= b.startTime })
        }
    }
}
