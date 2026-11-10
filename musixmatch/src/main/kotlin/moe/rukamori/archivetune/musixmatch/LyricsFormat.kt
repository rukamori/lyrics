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
import java.util.Locale

internal class LyricsFormat(private val jsonFormat: Json) {
    private val LICENSE_FOOTER_PATTERNS =
        listOf(
            "this lyrics is not for commercial use",
            "these lyrics are not for commercial use",
        )

    private val lrcTimestampRegex = Regex("""\[\d{1,2}:\d{2}([.:]\d{1,3})?\]""")

    fun tryTtml(richSync: String?): String? {
        if (richSync.isNullOrBlank()) return null
        return runCatching {
            val lines = jsonFormat.decodeFromString<List<RichSyncLine>>(richSync)
            MusixmatchTtml.convertRichSyncToTtml(lines)
        }.getOrNull()?.takeIf { it.isNotBlank() }
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
