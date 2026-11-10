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
data class RichSyncBody(
    val richsync: RichSyncData,
)

@Serializable
data class RichSyncData(
    @SerialName("richsync_id")
    val richsyncId: Long? = null,
    @SerialName("richsync_body")
    val richsyncBody: String = "",
)

@Serializable
data class RichSyncLine(
    val ts: Double,
    val te: Double,
    val l: List<RichSyncWord> = emptyList(),
    val x: String? = null,
)

@Serializable
data class RichSyncWord(
    val c: String,
    val o: Double,
)
