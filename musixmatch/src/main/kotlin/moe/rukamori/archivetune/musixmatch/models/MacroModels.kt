/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class MacroResponseBody(
    @SerialName("macro_calls") val macroCalls: MacroCalls? = null,
)

@Serializable
data class MacroCall(
    val message: MacroMessage = MacroMessage(),
)

@Serializable
data class MacroMessage(
    val header: MusixmatchHeader = MusixmatchHeader(),
    val body: JsonElement? = null,
)

@Serializable
data class MacroCalls(
    @SerialName("matcher.track.get") val matcherTrackGet: MacroCall? = null,
    @SerialName("track.lyrics.get") val trackLyricsGet: MacroCall? = null,
    @SerialName("track.subtitles.get") val trackSubtitlesGet: MacroCall? = null,
    @SerialName("track.richsync.get") val trackRichsyncGet: MacroCall? = null,
)

inline fun <reified T> MacroCall?.bodyAs(json: Json): T? {
    val body = this?.message?.body
    if (body == null || body !is JsonObject) return null
    return runCatching { json.decodeFromJsonElement<T>(body) }.getOrNull()
}

@Serializable
data class MatcherBody(
    val track: Track? = null,
)

@Serializable
data class Track(
    @SerialName("track_id") val trackId: Long = 0,
    @SerialName("commontrack_id") val commonTrackId: Long = 0,
    @SerialName("track_name") val trackName: String = "",
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("album_name") val albumName: String = "",
    @SerialName("track_rating") val trackRating: Int = 0,
    @SerialName("track_length") val trackLength: Int = 0,
    @SerialName("has_richsync") val hasRichsync: Int = 0,
    @SerialName("has_subtitles") val hasSubtitles: Int = 0,
    @SerialName("has_lyrics") val hasLyrics: Int = 0,
    @SerialName("has_lyrics_crowd") val hasLyricsCrowd: Int = 0,
    @SerialName("instrumental") val instrumental: Int = 0,
) {
    val isInstrumental: Boolean get() = instrumental == 1
    val isRichsyncAvailable: Boolean get() = hasRichsync == 1
    val isSubtitlesAvailable: Boolean get() = hasSubtitles == 1
    val isLyricsAvailable: Boolean get() = hasLyrics == 1 || hasLyricsCrowd == 1
}

@Serializable
data class SubtitlesBody(
    @SerialName("subtitle_list") val subtitleList: List<SubtitleItem> = emptyList(),
)

@Serializable
data class SubtitleItem(
    val subtitle: SubtitleData? = null,
)

@Serializable
data class SubtitleBody(
    val subtitle: SubtitleData? = null,
)

@Serializable
data class SubtitleData(
    @SerialName("subtitle_body") val subtitleBody: String? = null,
)

@Serializable
data class SubtitleLine(
    val text: String? = null,
    val time: SubtitleTime? = null,
)

@Serializable
data class SubtitleTime(
    val total: Double = 0.0,
)

@Serializable
data class LyricsBody(
    val lyrics: LyricsData? = null,
)

@Serializable
data class LyricsData(
    @SerialName("lyrics_id") val lyricsId: Long = 0,
    @SerialName("lyrics_body") val lyricsBody: String? = null,
    @SerialName("restricted") val restricted: Int = 0,
    @SerialName("instrumental") val instrumental: Int = 0,
) {
    val isRestricted: Boolean get() = restricted == 1
}

@Serializable
data class RichSyncBody(
    val richsync: RichSyncData? = null,
)

@Serializable
data class RichSyncData(
    @SerialName("richsync_id") val richsyncId: Long = 0,
    @SerialName("richsync_body") val richsyncBody: String? = null,
)
