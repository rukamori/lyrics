/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import moe.rukamori.archivetune.musixmatch.models.Track

internal data class ResolvedLyrics(
    val lyrics: String,
    val quality: Int,
)

internal data class SearchCandidate(
    val track: Track,
    val demerit: Int,
)

internal sealed interface MacroTarget {
    data class ByName(
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationSeconds: Int = 0,
    ) : MacroTarget

    data class ById(
        val trackId: Long,
        val durationSeconds: Int = 0,
    ) : MacroTarget
}

internal sealed interface CandidateOutcome {
    val candidate: SearchCandidate

    data class Success(
        override val candidate: SearchCandidate,
        val resolved: ResolvedLyrics,
    ) : CandidateOutcome

    data class Terminal(
        override val candidate: SearchCandidate,
        val error: MusixmatchError,
    ) : CandidateOutcome

    data class LocalFailure(
        override val candidate: SearchCandidate,
        val error: Exception,
    ) : CandidateOutcome
}

internal fun matchedTrack(calls: moe.rukamori.archivetune.musixmatch.models.MacroCalls?, expectedTrackId: Long? = null): Track {
    val matcherCall = calls?.matcherTrack
    val status = matcherCall?.message?.header?.statusCode ?: 0
    if (status != 200) throw MusixmatchError.NoMatch("Matcher status code is $status")
    val track = matcherCall?.message?.body?.track
        ?: throw MusixmatchError.NoMatch("No track matched")
    if (expectedTrackId != null && track.trackId != expectedTrackId) {
        throw MusixmatchError.Mismatch("Matcher track ID ${track.trackId} differs from expected $expectedTrackId")
    }
    return track
}

internal suspend fun resolveTier(
    quality: Int,
    inlineBody: String?,
    hasStandalone: Boolean = false,
    convert: (String?) -> String?,
    fetchStandalone: (suspend () -> String?)? = null,
): ResolvedLyrics? {
    convert(inlineBody)?.let { return ResolvedLyrics(it, quality) }

    if (hasStandalone && fetchStandalone != null) {
        val fetchedBody = try {
            fetchStandalone()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: MusixmatchError.TokenInvalid) {
            throw e
        } catch (e: MusixmatchError.RateLimited) {
            throw e
        } catch (e: Exception) {
            null
        }

        convert(fetchedBody)?.let { return ResolvedLyrics(it, quality) }
    }

    return null
}
