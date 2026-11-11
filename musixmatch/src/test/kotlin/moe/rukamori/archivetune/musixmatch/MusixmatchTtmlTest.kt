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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class MusixmatchTtmlTest {
    private val spicetifyFixtureLine =
        RichSyncLine(
            startTime = 7.748,
            endTime = 11.283,
            words =
                listOf(
                    RichSyncWord(text = "Don't", offset = 0.0),
                    RichSyncWord(text = " ", offset = 0.115),
                    RichSyncWord(text = "know", offset = 0.231),
                    RichSyncWord(text = " ", offset = 0.338),
                    RichSyncWord(text = "where", offset = 0.445),
                    RichSyncWord(text = " ", offset = 0.538),
                    RichSyncWord(text = "we", offset = 0.632),
                    RichSyncWord(text = " ", offset = 0.832),
                    RichSyncWord(text = "disconnected", offset = 1.033),
                    RichSyncWord(text = " ", offset = 1.772),
                    RichSyncWord(text = "each", offset = 2.074),
                    RichSyncWord(text = " ", offset = 2.185),
                    RichSyncWord(text = "of", offset = 2.297),
                    RichSyncWord(text = " ", offset = 2.453),
                    RichSyncWord(text = "our", offset = 2.609),
                    RichSyncWord(text = " ", offset = 2.755),
                    RichSyncWord(text = "stories", offset = 2.902),
                ),
            text = "Don't know where we disconnected each of our stories",
        )

    private fun parseXml(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(InputSource(StringReader(xml)))
        return doc.documentElement
    }

    private fun parseSeconds(attr: String): Double =
        attr.removeSuffix("s").toDouble()

    @Test
    fun testSpicetifyFixture_lastWordDurationIs0633() {
        val ttml = MusixmatchTtml.convertRichSyncToTtml(listOf(spicetifyFixtureLine))
        val root = parseXml(ttml)
        val p = root.getElementsByTagName("p").item(0) as Element
        val spans = p.getElementsByTagName("span")

        val lastSpan = spans.item(spans.length - 1) as Element
        assertEquals("stories", lastSpan.textContent)

        val begin = parseSeconds(lastSpan.getAttribute("begin"))
        val end = parseSeconds(lastSpan.getAttribute("end"))
        val duration = end - begin

        assertEquals(10.650, begin, 0.002)
        assertEquals(11.283, end, 0.002)
        assertEquals(0.633, duration, 0.002)
    }

    @Test
    fun testAllSpans_endGreaterThanBegin() {
        val ttml = MusixmatchTtml.convertRichSyncToTtml(listOf(spicetifyFixtureLine))
        val root = parseXml(ttml)
        val p = root.getElementsByTagName("p").item(0) as Element
        val spans = p.getElementsByTagName("span")

        assertTrue(spans.length > 0)
        for (i in 0 until spans.length) {
            val span = spans.item(i) as Element
            val begin = parseSeconds(span.getAttribute("begin"))
            val end = parseSeconds(span.getAttribute("end"))
            assertTrue("Span $i begin ($begin) must be less than end ($end)", end > begin)
        }
    }

    @Test
    fun testNoBlankSpans() {
        val ttml = MusixmatchTtml.convertRichSyncToTtml(listOf(spicetifyFixtureLine))
        val root = parseXml(ttml)
        val p = root.getElementsByTagName("p").item(0) as Element
        val spans = p.getElementsByTagName("span")

        for (i in 0 until spans.length) {
            val span = spans.item(i) as Element
            assertFalse("Span $i must not be blank", span.textContent.isBlank())
        }
    }

    @Test
    fun testReconstructedText_matchesOriginalX() {
        val ttml = MusixmatchTtml.convertRichSyncToTtml(listOf(spicetifyFixtureLine))
        val root = parseXml(ttml)
        val p = root.getElementsByTagName("p").item(0) as Element

        val reconstructed = p.textContent.trim().replace("\\s+".toRegex(), " ")
        assertEquals(spicetifyFixtureLine.text, reconstructed)
    }

    @Test
    fun testLocaleNonUs_usesDotDecimalSeparator() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("id", "ID"))
            val ttml = MusixmatchTtml.convertRichSyncToTtml(listOf(spicetifyFixtureLine))
            assertTrue("Output must use dot decimal, not comma", ttml.contains("7.748s"))
            assertFalse("Output must not contain comma decimals", ttml.contains("7,748s"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun testXmlEscaping_escapesSpecialCharacters() {
        val line =
            RichSyncLine(
                startTime = 1.0,
                endTime = 3.0,
                words =
                    listOf(
                        RichSyncWord(text = "Rock", offset = 0.0),
                        RichSyncWord(text = " ", offset = 0.5),
                        RichSyncWord(text = "&", offset = 0.6),
                        RichSyncWord(text = " ", offset = 1.0),
                        RichSyncWord(text = "<Roll>", offset = 1.2),
                    ),
                text = "Rock & <Roll>",
            )

        val ttml = MusixmatchTtml.convertRichSyncToTtml(listOf(line))
        assertTrue(ttml.contains("&amp;"))
        assertTrue(ttml.contains("&lt;Roll&gt;"))
        assertNotNull(parseXml(ttml))
    }

    @Test
    fun testChunksOutOfOrder_correctEndAndMaintainsOrder() {
        val line =
            RichSyncLine(
                startTime = 5.0,
                endTime = 10.0,
                words =
                    listOf(
                        RichSyncWord(text = "First", offset = 2.0),
                        RichSyncWord(text = " ", offset = 2.5),
                        RichSyncWord(text = "Second", offset = 0.5),
                    ),
                text = "First Second",
            )

        val ttml = MusixmatchTtml.convertRichSyncToTtml(listOf(line))
        val root = parseXml(ttml)
        val p = root.getElementsByTagName("p").item(0) as Element
        val spans = p.getElementsByTagName("span")

        assertEquals("First", spans.item(0).textContent)
        assertEquals("Second", spans.item(1).textContent)

        val firstBegin = parseSeconds((spans.item(0) as Element).getAttribute("begin"))
        val firstEnd = parseSeconds((spans.item(0) as Element).getAttribute("end"))
        val secondBegin = parseSeconds((spans.item(1) as Element).getAttribute("begin"))
        val secondEnd = parseSeconds((spans.item(1) as Element).getAttribute("end"))

        assertTrue("First span end must be > begin", firstEnd > firstBegin)
        assertTrue("Second span end must be > begin", secondEnd > secondBegin)
    }

    @Test
    fun testEmptyWords_withTextX_emitsLineSyncedP() {
        val line =
            RichSyncLine(
                startTime = 2.0,
                endTime = 4.0,
                words = emptyList(),
                text = "Line synced only text",
            )

        val ttml = MusixmatchTtml.convertRichSyncToTtml(listOf(line))
        val root = parseXml(ttml)
        val p = root.getElementsByTagName("p").item(0) as Element
        val spans = p.getElementsByTagName("span")

        assertEquals(0, spans.length)
        assertEquals("Line synced only text", p.textContent)
    }

    @Test
    fun testEndTimeLessThanOrEqualToStartTime_skipped() {
        val line =
            RichSyncLine(
                startTime = 5.0,
                endTime = 4.0,
                words = listOf(RichSyncWord(text = "Invalid", offset = 0.0)),
                text = "Invalid",
            )

        assertNull(MusixmatchTtml.convertLine(line))
    }

    @Test
    fun testRegression_shortLineCoerceIn_doesNotThrow() {
        val shortLine =
            RichSyncLine(
                startTime = 7.748,
                endTime = 7.7485,
                words = listOf(RichSyncWord(text = "TooShort", offset = 0.0)),
                text = "TooShort",
            )

        val result = MusixmatchTtml.convertLine(shortLine)
        assertNull("Line shorter than 2 * MIN_SPAN must return null", result)
    }

    @Test
    fun testRegression_shortLineBetweenValidLines_doesNotBreakOtherLines() {
        val valid1 =
            RichSyncLine(
                startTime = 1.0,
                endTime = 3.0,
                words = listOf(RichSyncWord(text = "ValidOne", offset = 0.0)),
                text = "ValidOne",
            )
        val shortLine =
            RichSyncLine(
                startTime = 3.1,
                endTime = 3.1005,
                words = listOf(RichSyncWord(text = "Bad", offset = 0.0)),
                text = "Bad",
            )
        val valid2 =
            RichSyncLine(
                startTime = 4.0,
                endTime = 6.0,
                words = listOf(RichSyncWord(text = "ValidTwo", offset = 0.0)),
                text = "ValidTwo",
            )

        val ttml = MusixmatchTtml.convertRichSyncToTtml(listOf(valid1, shortLine, valid2))
        val root = parseXml(ttml)
        val pTags = root.getElementsByTagName("p")
        assertEquals(2, pTags.length)
    }

    @Test
    fun testRegression_emptyLinesList_returnsEmptyString() {
        val result = MusixmatchTtml.convertRichSyncToTtml(emptyList())
        assertEquals("", result)
    }

    @Test
    fun testRegression_allInvalidLines_returnsEmptyString() {
        val invalid1 =
            RichSyncLine(
                startTime = 5.0,
                endTime = 4.0,
                words = listOf(RichSyncWord(text = "Invalid", offset = 0.0)),
                text = "Invalid",
            )
        val invalid2 =
            RichSyncLine(
                startTime = 7.0,
                endTime = 7.0001,
                words = listOf(RichSyncWord(text = "TooShort", offset = 0.0)),
                text = "TooShort",
            )

        val result = MusixmatchTtml.convertRichSyncToTtml(listOf(invalid1, invalid2))
        assertEquals("", result)
    }
}
