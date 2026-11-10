/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import moe.rukamori.archivetune.musixmatch.models.RichSyncLine
import java.util.Locale
import kotlin.math.max

internal object MusixmatchTtml {
    private const val MIN_SPAN = 0.001

    private sealed interface Token {
        val text: String

        data class Word(
            override val text: String,
            val start: Double,
            var end: Double = 0.0,
        ) : Token

        data class Gap(
            override val text: String,
        ) : Token
    }

    fun formatTtmlTime(seconds: Double): String =
        String.format(Locale.US, "%.3fs", seconds)

    fun escapeXml(value: String): String =
        buildString(value.length) {
            for (char in value) {
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(char)
                }
            }
        }

    fun convertLine(line: RichSyncLine): String? {
        val words = line.words
        val rawText = line.text

        if (words.isEmpty() && rawText.isNullOrBlank()) {
            return null
        }

        val lineStart = line.startTime
        val lineEnd = line.endTime
        if (lineEnd <= lineStart || lineEnd - lineStart < 2 * MIN_SPAN) {
            return null
        }

        val tokens = mutableListOf<Token>()
        val wordIndices = mutableListOf<Int>()

        for (item in words) {
            val c = item.text
            if (c.isBlank()) {
                tokens.add(Token.Gap(text = c))
            } else {
                val clampedStart = (lineStart + item.offset).coerceIn(lineStart, lineEnd - MIN_SPAN)
                tokens.add(Token.Word(text = c, start = clampedStart))
                wordIndices.add(tokens.lastIndex)
            }
        }

        if (wordIndices.isEmpty()) {
            val escapedText = escapeXml(rawText.orEmpty())
            return "<p begin=\"${formatTtmlTime(lineStart)}\" end=\"${formatTtmlTime(lineEnd)}\">$escapedText</p>"
        }

        val sortedIndices = wordIndices.sortedBy { (tokens[it] as Token.Word).start }
        for (i in sortedIndices.indices) {
            val currentWord = tokens[sortedIndices[i]] as Token.Word
            val nextStart =
                if (i + 1 < sortedIndices.size) {
                    (tokens[sortedIndices[i + 1]] as Token.Word).start
                } else {
                    lineEnd
                }
            currentWord.end = max(nextStart, currentWord.start + MIN_SPAN)
        }

        val inner = buildString {
            for (token in tokens) {
                when (token) {
                    is Token.Word -> {
                        append("<span begin=\"")
                        append(formatTtmlTime(token.start))
                        append("\" end=\"")
                        append(formatTtmlTime(token.end))
                        append("\">")
                        append(escapeXml(token.text))
                        append("</span>")
                    }
                    is Token.Gap -> {
                        append(escapeXml(token.text))
                    }
                }
            }
        }

        return "<p begin=\"${formatTtmlTime(lineStart)}\" end=\"${formatTtmlTime(lineEnd)}\">$inner</p>"
    }

    fun convertRichSyncToTtml(lines: List<RichSyncLine>): String {
        val pTags = lines.mapNotNull(::convertLine)
        if (pTags.isEmpty()) return ""
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\">\n")
            append("  <body>\n")
            append("    <div>\n")
            for (p in pTags) {
                append("      ")
                append(p)
                append("\n")
            }
            append("    </div>\n")
            append("  </body>\n")
            append("</tt>")
        }
    }
}
