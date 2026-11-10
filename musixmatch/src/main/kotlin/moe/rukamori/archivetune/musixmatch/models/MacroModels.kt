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
data class MatcherTrackBody(
    val track: Track,
)

@Serializable
data class MacroBody(
    @SerialName("macro_calls")
    val macroCalls: MacroCalls,
)

@Serializable
data class MacroCalls(
    @SerialName("matcher.track.get")
    val matcherTrack: MusixmatchResponse<MatcherTrackBody>? = null,
    @SerialName("track.lyrics.get")
    val lyrics: MusixmatchResponse<LyricsBody>? = null,
    @SerialName("track.subtitles.get")
    val subtitles: MusixmatchResponse<SubtitlesBody>? = null,
    @SerialName("track.richsync.get")
    val richsync: MusixmatchResponse<RichSyncBody>? = null,
)
