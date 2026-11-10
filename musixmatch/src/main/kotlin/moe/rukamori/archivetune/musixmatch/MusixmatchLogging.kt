/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import moe.rukamori.archivetune.musixmatch.models.TrackSearchBody

internal fun errorLabel(e: Throwable): String = when (e) {
    is MusixmatchError.TokenInvalid -> "TokenInvalid"
    is MusixmatchError.RateLimited -> "RateLimited"
    is MusixmatchError.NoMatch -> "NoMatch"
    is MusixmatchError.Mismatch -> "Mismatch"
    is MusixmatchError.Restricted -> "Restricted"
    is MusixmatchError.Instrumental -> "Instrumental"
    is MusixmatchError.ServerError -> "ServerError"
    is MusixmatchError.MalformedResponse -> "MalformedResponse"
    else -> "OtherException"
}

internal fun logSearchFunnel(
    label: String,
    body: TrackSearchBody,
    durationSeconds: Int,
    selectedCount: Int,
) {
    Musixmatch.logger?.let { log ->
        val eligible = body.trackList.count { isSearchCandidate(it.track, durationSeconds) }
        log("Musixmatch $label: search funnel: returned=${body.trackList.size} eligible=$eligible candidates=$selectedCount")
    }
}
