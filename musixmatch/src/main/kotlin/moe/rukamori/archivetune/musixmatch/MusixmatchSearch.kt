/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import moe.rukamori.archivetune.musixmatch.models.SearchBody
import moe.rukamori.archivetune.musixmatch.models.Track
import kotlin.math.abs

internal const val DURATION_TOLERANCE = 8
internal const val SEARCH_FALLBACK_MAX_ATTEMPTS = 2

internal fun selectSearchCandidates(
    body: SearchBody,
    inputTitle: String,
    durationSeconds: Int,
    limit: Int,
): List<SearchCandidate> = body.trackList
    .mapNotNull { it.track }
    .filter { track -> isSearchCandidate(track, durationSeconds) }
    .distinctBy { it.trackId }
    .let { tracks ->
        val inputMarkers = versionMarkers(inputTitle)
        tracks.map { track -> SearchCandidate(track, versionDemerit(track, inputMarkers)) }
            .sortedWith(compareBy<SearchCandidate> { it.demerit }.thenByDescending { it.track.trackRating })
            .take(limit)
    }

private fun isSearchCandidate(track: Track, durationSeconds: Int): Boolean =
    track.trackId > 0 && !track.isInstrumental &&
        (durationSeconds <= 0 || track.trackLength <= 0 ||
            abs(track.trackLength - durationSeconds) <= DURATION_TOLERANCE)

internal fun Musixmatch.logSearchFunnel(pathLabel: String, body: SearchBody, durationSeconds: Int, selectedCount: Int) {
    logger?.let { log ->
        val eligible = body.trackList.count { it.track?.let { track -> isSearchCandidate(track, durationSeconds) } == true }
        log("Musixmatch $pathLabel: search funnel: returned=${body.trackList.size} eligible=$eligible candidates=$selectedCount")
    }
}

private fun normalizeString(value: String): String =
    value.filter { it.isLetterOrDigit() }.lowercase()

internal fun validateMatch(
    track: Track?,
    inputTitle: String,
    inputArtist: String,
    inputDuration: Int,
) {
    if (track == null) {
        throw MusixmatchError.NoMatch("No track matched")
    }

    if (inputDuration > 0 && track.trackLength > 0) {
        if (abs(track.trackLength - inputDuration) > DURATION_TOLERANCE) {
            throw MusixmatchError.Mismatch(
                "Track length ${track.trackLength} differs from input $inputDuration by > $DURATION_TOLERANCE",
            )
        }
    }

    val normTrackTitle = normalizeString(track.trackName)
    val normInputTitle = normalizeString(inputTitle)
    val normTrackArtist = normalizeString(track.artistName)
    val normInputArtist = normalizeString(inputArtist)

    val titleOk =
        normTrackTitle.isNotEmpty() &&
            normInputTitle.isNotEmpty() &&
            (normTrackTitle.contains(normInputTitle) || normInputTitle.contains(normTrackTitle))
    val artistOk =
        normTrackArtist.isNotEmpty() &&
            normInputArtist.isNotEmpty() &&
            (normTrackArtist.contains(normInputArtist) || normInputArtist.contains(normTrackArtist))

    if (!titleOk && !artistOk) {
        throw MusixmatchError.Mismatch("Track and artist both mismatched")
    }
}

private val qualifierPattern = Regex("""\([^)]*\)|\[[^]]*\]| [-–] (.+)$""")
private val markerPattern = Regex("""(?i)(?<![\p{L}\p{N}])(?:remix|live|covers?|instrumental|acoustic|arrangement|tribute|karaoke)(?![\p{L}\p{N}])""")

private fun versionMarkers(text: String): Int {
    var markers = 0
    for (match in markerPattern.findAll(text)) {
        markers = markers or when (match.value.lowercase(java.util.Locale.ROOT)) {
            "remix" -> 1
            "live" -> 2
            "cover", "covers" -> 4
            "instrumental" -> 8
            "acoustic" -> 16
            "arrangement" -> 32
            "tribute" -> 64
            else -> 128
        }
    }
    return markers
}

private fun versionDemerit(track: Track, inputMarkers: Int): Int {
    var markers = versionMarkers(track.albumName) and 4
    for (qualifier in qualifierPattern.findAll(track.trackName)) {
        markers = markers or versionMarkers(qualifier.value)
    }
    return if ((markers xor inputMarkers) != 0) 1 else 0
}

internal val FALLBACK_SUCCESS_COMPARATOR: Comparator<CandidateOutcome.Success> =
    compareBy<CandidateOutcome.Success> { it.candidate.demerit }
        .thenByDescending { it.lyrics.quality }
        .thenByDescending { it.candidate.track.trackRating }
        .thenBy { it.rank }

internal suspend fun Musixmatch.resolveSearchFallback(
    tokenState: SearchTokenState,
    title: String,
    artist: String,
    durationSeconds: Int,
): String {
    logger?.invoke("Musixmatch fallback: ranked search")
    val body = tokenState.execute { token -> executeTrackSearch(title, artist, token, "fallback") }
    val candidates = selectSearchCandidates(body, title, durationSeconds, SEARCH_FALLBACK_MAX_ATTEMPTS)
    logSearchFunnel("fallback", body, durationSeconds, candidates.size)
    if (candidates.isEmpty()) throw MusixmatchError.NoMatch("Search fallback returned no usable lyrics")

    val outcomes = mutableListOf<CandidateOutcome>()
    coroutineScope {
        val channel = Channel<CandidateOutcome>()
        val loggedOutcome = Array(candidates.size) { AtomicBoolean(false) }
        val jobs = mutableListOf<Job>()
        for (index in candidates.indices) {
            val candidate = candidates[index]
            val rank = index + 1
            val job = launch {
                val outcome = try {
                    val resolved = resolveRequest(
                        MacroTarget.ById(candidate.track.trackId, title, artist, durationSeconds),
                        tokenState,
                        "fallback ranked $rank",
                    )
                    if (loggedOutcome[index].compareAndSet(false, true)) {
                        logger?.invoke("Musixmatch fallback ranked $rank: success demerit=${candidate.demerit} quality=${resolved.quality} rating=${candidate.track.trackRating}")
                    }
                    CandidateOutcome.Success(candidate, rank, resolved)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MusixmatchError.Instrumental) {
                    if (loggedOutcome[index].compareAndSet(false, true)) {
                        logger?.invoke("Musixmatch fallback ranked $rank: instrumental")
                    }
                    CandidateOutcome.LocalFailure(candidate, rank, e)
                } catch (e: MusixmatchError.TokenInvalid) {
                    if (loggedOutcome[index].compareAndSet(false, true)) {
                        logger?.invoke("Musixmatch fallback ranked $rank: failed (${errorLabel(e)})")
                    }
                    CandidateOutcome.TerminalFailure(candidate, rank, e)
                } catch (e: MusixmatchError.RateLimited) {
                    if (loggedOutcome[index].compareAndSet(false, true)) {
                        logger?.invoke("Musixmatch fallback ranked $rank: failed (${errorLabel(e)})")
                    }
                    CandidateOutcome.TerminalFailure(candidate, rank, e)
                } catch (e: Exception) {
                    if (loggedOutcome[index].compareAndSet(false, true)) {
                        logger?.invoke("Musixmatch fallback ranked $rank: failed (${errorLabel(e)})")
                    }
                    CandidateOutcome.LocalFailure(candidate, rank, e)
                }
                channel.send(outcome)
            }
            jobs.add(job)
        }
        launch {
            jobs.joinAll()
            channel.close()
        }

        for (outcome in channel) {
            outcomes.add(outcome)

            val successes = outcomes.filterIsInstance<CandidateOutcome.Success>()
            val bestSuccess = successes.minWithOrNull(FALLBACK_SUCCESS_COMPARATOR)
            if (bestSuccess != null) {
                val remainingCandidates = candidates.filterIndexed { idx, _ -> outcomes.none { it.rank == idx + 1 } }
                val isUnbeatable = remainingCandidates.all { remaining ->
                    val maxPossibleRemaining = CandidateOutcome.Success(
                        remaining,
                        candidates.indexOf(remaining) + 1,
                        ResolvedLyrics("", quality = 2),
                    )
                    FALLBACK_SUCCESS_COMPARATOR.compare(bestSuccess, maxPossibleRemaining) <= 0
                }
                if (isUnbeatable && remainingCandidates.isNotEmpty()) {
                    logger?.invoke("Musixmatch fallback: early-stop")
                    jobs.forEachIndexed { idx, job ->
                        val r = idx + 1
                        if (r != bestSuccess.rank && loggedOutcome[idx].compareAndSet(false, true)) {
                            logger?.invoke("Musixmatch fallback ranked $r: cancelled (early-stop)")
                            job.cancel()
                        }
                    }
                    channel.cancel()
                    break
                }
            }
        }
    }

    val bestSuccess = outcomes.filterIsInstance<CandidateOutcome.Success>()
        .minWithOrNull(FALLBACK_SUCCESS_COMPARATOR)
    if (bestSuccess != null) {
        logger?.invoke("Musixmatch fallback: winner from ranked ${bestSuccess.rank}")
        return bestSuccess.lyrics.lyrics
    }

    val terminals = outcomes.filterIsInstance<CandidateOutcome.TerminalFailure>()
    if (terminals.isNotEmpty()) {
        val bestTerminal = terminals.minByOrNull { it.rank }!!
        logger?.invoke("Musixmatch fallback: final terminal (${errorLabel(bestTerminal.error)}) from ranked ${bestTerminal.rank}")
        throw bestTerminal.error
    }

    val localFailures = outcomes.filterIsInstance<CandidateOutcome.LocalFailure>()
    if (localFailures.isNotEmpty()) {
        val bestLocal = localFailures.minByOrNull { it.rank }!!
        logger?.invoke("Musixmatch fallback: all candidates failed (${errorLabel(bestLocal.error)}) from ranked ${bestLocal.rank}")
        throw bestLocal.error
    }

    throw MusixmatchError.NoMatch("Search fallback returned no usable lyrics")
}
