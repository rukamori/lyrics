/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrackSearchBody(
    @SerialName("track_list")
    val trackList: List<TrackContainer> = emptyList(),
)

@Serializable
data class TrackContainer(
    val track: Track,
)

@Serializable
data class SubtitleBody(
    val subtitle: SubtitleData,
)

@Serializable
data class SubtitlesBody(
    @SerialName("subtitle_list")
    val subtitleList: List<SubtitleItem> = emptyList(),
)

@Serializable
data class SubtitleItem(
    val subtitle: SubtitleData? = null,
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
data class SubtitleData(
    @SerialName("subtitle_id")
    val subtitleId: Long? = null,
    @SerialName("restricted")
    val restricted: Int = 0,
    @SerialName("subtitle_body")
    val subtitleBody: String = "",
)

@Serializable
data class LyricsBody(
    val lyrics: LyricsData,
)

@Serializable
data class LyricsData(
    @SerialName("lyrics_id")
    val lyricsId: Long? = null,
    @SerialName("restricted")
    val restricted: Int = 0,
    @SerialName("instrumental")
    val instrumental: Int = 0,
    @SerialName("lyrics_body")
    val lyricsBody: String = "",
)
