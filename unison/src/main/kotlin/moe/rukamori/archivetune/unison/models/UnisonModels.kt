/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.unison.models

import kotlinx.serialization.Serializable

@Serializable
data class UnisonSubmitter(
    val keyId: String? = null,
    val displayName: String? = null,
)

@Serializable
data class UnisonEntry(
    val id: Long,
    val videoId: String? = null,
    val lyrics: String? = null,
    val format: String? = null,
    val syncType: String? = null,
    val matchedBy: String? = null,
    val hidden: Boolean = false,
    val submitter: UnisonSubmitter? = null,
)

@Serializable
data class UnisonResponse(
    val success: Boolean = false,
    val data: UnisonEntry? = null,
    val code: String? = null,
)

@Serializable
data class UnisonListResponse(
    val success: Boolean = false,
    val data: List<UnisonEntry>? = null,
    val code: String? = null,
)

data class UnisonLyrics(
    val id: Long,
    val videoId: String?,
    val lyrics: String,
    val format: String?,
    val syncType: String?,
    val submitterKeyId: String?,
    val submitterName: String?,
)

internal fun UnisonEntry.toLyrics(): UnisonLyrics? {
    if (hidden) return null
    val body = lyrics?.takeIf { it.isNotBlank() } ?: return null
    return UnisonLyrics(
        id = id,
        videoId = videoId?.trim()?.takeIf { it.isNotEmpty() },
        lyrics = body,
        format = format,
        syncType = syncType,
        submitterKeyId = submitter?.keyId?.trim()?.takeIf { it.isNotEmpty() },
        submitterName = submitter?.displayName?.trim()?.takeIf { it.isNotEmpty() },
    )
}
