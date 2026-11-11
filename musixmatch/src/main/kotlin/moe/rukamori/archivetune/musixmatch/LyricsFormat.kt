/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.musixmatch.models.RichSyncLine
import moe.rukamori.archivetune.musixmatch.models.SubtitleLine
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal data class RichSyncAnalysis(
    val nonEmptyLines: Int,
    val timedLines: Int,
    val timedRatio: Double,
    val wordSync: Boolean,
)

internal enum class LrcMatch {
    ABSENT,
    EQUIVALENT,
    MISMATCH,
}

internal data class FormattedRichSync(
    val content: String,
    val quality: Int,
)

internal data class ParsedLrcLine(
    val seconds: Double,
    val text: String,
)

internal class LyricsFormat(private val jsonFormat: Json) {
    private val LICENSE_FOOTER_PATTERNS =
        listOf(
            "this lyrics is not for commercial use",
            "these lyrics are not for commercial use",
        )

    private val lrcTimestampRegex = Regex("""\[\d{1,2}:\d{2}([.:]\d{1,3})?\]""")
    private val lrcTimestampTokenRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")

    fun tryTtml(richSync: String?): String? {
        if (richSync.isNullOrBlank()) return null
        val lines = runCatching {
            jsonFormat.decodeFromString<List<RichSyncLine>>(richSync)
        }.getOrNull() ?: return null
        if (lines.isEmpty()) return null
        val analysis = analyzeRichSync(lines)
        if (!analysis.wordSync) return null
        return MusixmatchTtml.convertRichSyncToTtml(lines).takeIf { it.isNotBlank() }
    }

    fun tryLrc(subtitleBody: String?): String? {
        if (subtitleBody.isNullOrBlank()) return null
        return formatSubtitlesToLrc(subtitleBody)?.takeIf { it.isNotBlank() }
            ?: subtitleBody.takeIf { lrcTimestampRegex.containsMatchIn(it) }?.trim()?.takeIf { it.isNotBlank() }
    }

    fun tryPlain(lyricsBody: String?): String? {
        if (lyricsBody.isNullOrBlank()) return null
        return cleanPlainLyrics(lyricsBody).takeIf { it.isNotBlank() }
    }

    fun analyzeRichSync(lines: List<RichSyncLine>): RichSyncAnalysis {
        var nonEmptyLines = 0
        var timedLines = 0
        for (line in lines) {
            val nonBlankWords = line.words.filter { it.text.isNotBlank() }
            val hasContent = nonBlankWords.isNotEmpty() || !line.text.isNullOrBlank()
            if (!hasContent) continue
            nonEmptyLines++
            if (nonBlankWords.size >= 2) {
                val minOffset = nonBlankWords.minOf { it.offset }
                val maxOffset = nonBlankWords.maxOf { it.offset }
                if (maxOffset - minOffset > 0.05) {
                    timedLines++
                }
            }
        }
        val timedRatio = if (nonEmptyLines > 0) timedLines.toDouble() / nonEmptyLines else 0.0
        val wordSync = nonEmptyLines > 0 && timedRatio >= 0.5
        return RichSyncAnalysis(
            nonEmptyLines = nonEmptyLines,
            timedLines = timedLines,
            timedRatio = timedRatio,
            wordSync = wordSync,
        )
    }

    fun parseLrc(subtitleBody: String?): List<ParsedLrcLine>? {
        if (subtitleBody.isNullOrBlank()) return null
        val fromJson = runCatching {
            val decoded = jsonFormat.decodeFromString<List<SubtitleLine>>(subtitleBody)
            if (decoded.isEmpty()) null
            else decoded.map { ParsedLrcLine(it.time?.total ?: 0.0, it.text?.trim().orEmpty()) }.sortedBy { it.seconds }
        }.getOrNull()
        if (fromJson != null) return fromJson

        val rawLines = subtitleBody.lines()
        val parsedLines = mutableListOf<ParsedLrcLine>()
        for (raw in rawLines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val matches = lrcTimestampTokenRegex.findAll(line).toList()
            if (matches.isEmpty() || matches.first().range.first != 0) continue
            val text = line.substring(matches.last().range.last + 1).trim()
            for (match in matches) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val fracStr = match.groupValues[3]
                val ms = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100L
                    2 -> fracStr.toLong() * 10L
                    else -> fracStr.take(3).toLong()
                }
                val totalSec = min * 60.0 + sec + ms / 1000.0
                parsedLines.add(ParsedLrcLine(totalSec, text))
            }
        }
        return parsedLines.takeIf { it.isNotEmpty() }?.sortedBy { it.seconds }
    }

    fun compareWithLrc(lines: List<RichSyncLine>, subtitleBody: String?): LrcMatch {
        val lrcLines = parseLrc(subtitleBody) ?: return LrcMatch.ABSENT

        val filteredLrc = lrcLines.mapNotNull { line ->
            val norm = normalizeLineText(line.text)
            if (norm.isEmpty() || norm == "♪") null
            else ParsedLrcLine(line.seconds, norm)
        }

        val filteredRich = lines.mapNotNull { line ->
            val raw = extractRichSyncText(line)
            val norm = normalizeLineText(raw)
            if (norm.isEmpty() || norm == "♪") null
            else ParsedLrcLine(line.startTime, norm)
        }

        if (filteredRich.isEmpty() || filteredLrc.isEmpty() || filteredRich.size != filteredLrc.size) {
            return LrcMatch.MISMATCH
        }

        for (i in filteredRich.indices) {
            val rich = filteredRich[i]
            val lrc = filteredLrc[i]
            if (rich.text != lrc.text) return LrcMatch.MISMATCH
            if (abs(rich.seconds - lrc.seconds) > 0.05) return LrcMatch.MISMATCH
        }
        return LrcMatch.EQUIVALENT
    }

    fun synthesizeLrc(lines: List<RichSyncLine>): String {
        return lines.mapNotNull { line ->
            val text = extractRichSyncText(line).trim()
            if (text.isEmpty()) null
            else {
                val timestamp = formatLrcTimestamp(line.startTime)
                "$timestamp $text"
            }
        }.joinToString("\n")
    }

    fun formatRichSync(richSync: String?, subtitleBody: String?): FormattedRichSync? {
        if (richSync.isNullOrBlank()) return null
        val lines = runCatching {
            jsonFormat.decodeFromString<List<RichSyncLine>>(richSync)
        }.getOrNull() ?: return null
        if (lines.isEmpty()) return null

        val analysis = analyzeRichSync(lines)
        if (analysis.wordSync) {
            val ttml = MusixmatchTtml.convertRichSyncToTtml(lines).takeIf { it.isNotBlank() }
                ?: return null
            return FormattedRichSync(ttml, 2)
        }

        val match = compareWithLrc(lines, subtitleBody)
        if (match == LrcMatch.EQUIVALENT) {
            val officialLrc = tryLrc(subtitleBody)
            if (!officialLrc.isNullOrBlank()) {
                return FormattedRichSync(officialLrc, 1)
            }
        }

        val synthLrc = synthesizeLrc(lines).takeIf { it.isNotBlank() }
        if (synthLrc != null) {
            return FormattedRichSync(synthLrc, 1)
        }

        val fallbackLrc = tryLrc(subtitleBody)
        if (!fallbackLrc.isNullOrBlank()) {
            return FormattedRichSync(fallbackLrc, 1)
        }
        return null
    }

    private fun extractRichSyncText(line: RichSyncLine): String {
        if (!line.text.isNullOrBlank()) return line.text
        return line.words.filter { it.text.isNotBlank() }.joinToString("") { it.text }
    }

    private fun normalizeLineText(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun formatLrcTimestamp(seconds: Double): String {
        val ms = (seconds * 1000).toLong().coerceAtLeast(0L)
        return String.format(
            Locale.US,
            "[%02d:%02d.%03d]",
            ms / 60000L,
            (ms % 60000L) / 1000L,
            ms % 1000L,
        )
    }

    private fun formatSubtitlesToLrc(subtitlesJson: String): String? =
        runCatching {
            val lines = jsonFormat.decodeFromString<List<SubtitleLine>>(subtitlesJson)
            if (lines.isEmpty()) return null
            lines
                .joinToString("\n") { line ->
                    val timestamp = formatLrcTimestamp(line.time?.total ?: 0.0)
                    val text = line.text?.trim()?.ifEmpty { "♪" } ?: "♪"
                    "$timestamp $text"
                }.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun cleanPlainLyrics(rawBody: String): String {
        val lines = rawBody.lines()
        val filtered =
            lines.filterNot { line ->
                val lower = line.lowercase()
                LICENSE_FOOTER_PATTERNS.any { lower.contains(it) }
            }
        return filtered.joinToString("\n").trim()
    }
}
