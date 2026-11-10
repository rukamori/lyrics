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

internal class LyricsFormat(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val licenseFooterPatterns = listOf(
        "this lyrics is not for commercial use",
        "these lyrics are not for commercial use",
    )

    fun tryTtml(richsyncBody: String?): String? {
        if (richsyncBody.isNullOrBlank()) return null
        return runCatching {
            val lines = json.decodeFromString<List<RichSyncLine>>(richsyncBody)
            MusixmatchTtml.convertRichSyncToTtml(lines).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun tryLrc(subtitleBody: String?): String? {
        if (subtitleBody.isNullOrBlank()) return null

        // If it is already in standard LRC format
        val trimmed = subtitleBody.trim()
        if (trimmed.startsWith("[") && trimmed.lines().any { it.matches(LRC_LINE_REGEX) }) {
            return trimmed
        }

        // Try decoding as Musixmatch JSON subtitle lines
        return runCatching {
            val lines = json.decodeFromString<List<SubtitleLine>>(subtitleBody)
            if (lines.isEmpty()) return null
            lines.joinToString("\n") { line ->
                val timestamp = formatLrcTimestamp(line.time?.total ?: 0.0)
                val text = line.text?.trim()?.ifEmpty { "♪" } ?: "♪"
                "$timestamp $text"
            }.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun tryPlain(lyricsBody: String?): String? {
        if (lyricsBody.isNullOrBlank()) return null
        val lines = lyricsBody.lines()
        val filtered = lines.filterNot { line ->
            val lower = line.lowercase()
            licenseFooterPatterns.any { lower.contains(it) }
        }
        return filtered.joinToString("\n").trim().takeIf { it.isNotBlank() }
    }

    fun formatLrcTimestamp(seconds: Double): String {
        val ms = (seconds * 1000).toLong().coerceAtLeast(0L)
        return String.format(
            Locale.US,
            "[%02d:%02d.%03d]",
            ms / 60000L,
            (ms % 60000L) / 1000L,
            ms % 1000L,
        )
    }

    companion object {
        private val LRC_LINE_REGEX = Regex("""^\[\d{2}:\d{2}\.\d{2,3}].*""")
    }
}
