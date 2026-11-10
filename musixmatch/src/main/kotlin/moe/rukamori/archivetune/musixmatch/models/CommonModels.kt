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
data class MusixmatchResponse<T>(
    val message: Message<T>,
)

@Serializable
data class Message<T>(
    val header: Header,
    val body: T? = null,
)

@Serializable
data class Header(
    @SerialName("status_code")
    val statusCode: Int,
)

@Serializable
data class Track(
    @SerialName("track_id")
    val trackId: Long,
    @SerialName("track_name")
    val trackName: String,
    @SerialName("artist_name")
    val artistName: String,
    @SerialName("track_length")
    val trackLength: Int = 0,
    @SerialName("instrumental")
    val instrumental: Int = 0,
    @SerialName("restricted")
    val restricted: Int = 0,
    @SerialName("has_lyrics")
    val hasLyrics: Int = 0,
    @SerialName("has_subtitles")
    val hasSubtitles: Int = 0,
    @SerialName("has_richsync")
    val hasRichsync: Int = 0,
    @SerialName("track_rating")
    val trackRating: Int = 0,
    @SerialName("commontrack_id")
    val commontrackId: Long? = null,
    @SerialName("album_name")
    val albumName: String? = null,
)
