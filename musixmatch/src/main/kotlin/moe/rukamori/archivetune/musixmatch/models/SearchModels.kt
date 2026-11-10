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
data class SearchBody(
    @SerialName("track_list") val trackList: List<SearchTrackItem> = emptyList(),
)

@Serializable
data class SearchTrackItem(
    val track: Track? = null,
)
