/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.rukamori.archivetune.musixmatch.models.Track
import moe.rukamori.archivetune.musixmatch.models.TrackSearchBody
import java.util.Locale
import kotlin.math.abs

internal const val DURATION_TOLERANCE = 8
internal const val SEARCH_FALLBACK_MAX_ATTEMPTS = 2
internal const val SEARCH_MAX_CANDIDATES = 4
internal const val SEARCH_CONCURRENCY = 2

internal fun selectSearchCandidates(
    body: TrackSearchBody,
    inputTitle: String,
    durationSeconds: Int,
    limit: Int,
): List<SearchCandidate> = body.trackList
    .map { it.track }
    .filter { track -> isSearchCandidate(track, durationSeconds) }
    .distinctBy { it.trackId }
    .let { tracks ->
        val inputMarkers = versionMarkers(inputTitle)
        tracks.map { track -> SearchCandidate(track, versionDemerit(track, inputMarkers)) }
            .sortedWith(
                compareBy<SearchCandidate> { it.demerit }
                    .thenByDescending { it.track.trackRating }
            )
            .take(limit)
    }

internal fun isSearchCandidate(track: Track, durationSeconds: Int): Boolean =
    track.trackId > 0 &&
        track.instrumental == 0 &&
        (durationSeconds <= 0 || track.trackLength <= 0 ||
            abs(track.trackLength - durationSeconds) <= DURATION_TOLERANCE)

internal fun normalizeString(value: String): String =
    value.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)

internal fun validateMatch(
    track: Track?,
    inputTitle: String? = null,
    inputArtist: String? = null,
    inputDuration: Int = 0,
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

    val normInputTitle = inputTitle?.let(::normalizeString).orEmpty()
    val normInputArtist = inputArtist?.let(::normalizeString).orEmpty()

    // Caller-supplied verification: if both title and artist are empty, skip validation
    if (normInputTitle.isEmpty() && normInputArtist.isEmpty()) {
        return
    }

    val normTrackTitle = normalizeString(track.trackName)
    val normTrackArtist = normalizeString(track.artistName)

    val titleOk = normInputTitle.isNotEmpty() && normTrackTitle.isNotEmpty() &&
        (normTrackTitle.contains(normInputTitle) || normInputTitle.contains(normTrackTitle))
    val artistOk = normInputArtist.isNotEmpty() && normTrackArtist.isNotEmpty() &&
        (normTrackArtist.contains(normInputArtist) || normTrackArtist.contains(normTrackArtist))

    if (!titleOk && !artistOk) {
        throw MusixmatchError.Mismatch("Track and artist both mismatched")
    }
}

private val qualifierPattern = Regex("""\([^)]*\)|\[[^]]*\]| [-–] (.+)$""")
private val markerPattern = Regex("""(?i)(?<![\p{L}\p{N}])(?:remix|live|covers?|instrumental|acoustic|arrangement|tribute|karaoke)(?![\p{L}\p{N}])""")

internal fun versionMarkers(text: String): Int {
    var markers = 0
    for (match in markerPattern.findAll(text)) {
        markers = markers or when (match.value.lowercase(Locale.ROOT)) {
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

internal fun versionDemerit(track: Track, inputMarkers: Int): Int {
    var markers = versionMarkers(track.albumName.orEmpty()) and 4
    for (qualifier in qualifierPattern.findAll(track.trackName)) {
        markers = markers or versionMarkers(qualifier.value)
    }
    return if ((markers xor inputMarkers) != 0) 1 else 0
}

internal suspend fun Musixmatch.executeTrackSearch(
    title: String,
    artist: String,
    token: String,
    contextLabel: String = "fan-out",
): TrackSearchBody {
    logger?.invoke("Musixmatch $contextLabel: track search request started")
    val response = client.get("${Musixmatch.BASE_URL}track.search") {
        parameter("q_track", title)
        parameter("q_artist", artist)
        parameter("page_size", Musixmatch.SEARCH_PAGE_SIZE)
        parameter("f_has_lyrics", 1)
        parameter("app_id", Musixmatch.APP_ID)
        parameter("usertoken", token)
    }
    return decodeApiResponse(response, "track.search")
}

internal suspend fun Musixmatch.executeSearchGetAllLyrics(
    title: String,
    artist: String,
    album: String? = null,
    durationSeconds: Int = -1,
    callback: (String) -> Unit,
) = coroutineScope {
    val cleanTitle = title.trim()
    val cleanArtist = artist.trim()
    val cleanAlbum = album?.trim().orEmpty()

    if (cleanTitle.isEmpty() || cleanArtist.isEmpty()) return@coroutineScope

    val duration = durationSeconds.coerceAtLeast(0)
    val tokenState = SearchTokenState(getToken()) { renewToken() }

    // 1. Search tracks
    val searchBody = try {
        tokenState.execute { token -> executeTrackSearch(cleanTitle, cleanArtist, token, "fan-out") }
    } catch (e: MusixmatchError) {
        return@coroutineScope
    }

    // 2. Filter candidates (limit 4)
    val candidates = selectSearchCandidates(
        body = searchBody,
        inputTitle = cleanTitle,
        durationSeconds = duration,
        limit = SEARCH_MAX_CANDIDATES,
    )
    logSearchFunnel("fan-out", searchBody, duration, candidates.size)

    // 3. Special branch: Fallback to title matcher ONLY when search candidates list is empty (§2.6)
    if (candidates.isEmpty()) {
        logger?.invoke("Musixmatch title-fallback: title matcher")
        try {
            val resolved = resolveRequest(
                target = MacroTarget.ByName(cleanTitle, cleanArtist, cleanAlbum, duration),
                tokenState = tokenState,
                verifyTitle = cleanTitle,
                verifyArtist = cleanArtist,
                verifyDuration = duration,
                contextLabel = "title-fallback",
            )
            logger?.invoke("Musixmatch title-fallback: success quality=${resolved.quality}")
            callback(resolved.lyrics)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MusixmatchError.Instrumental) {
            logger?.invoke("Musixmatch title-fallback: instrumental")
        } catch (e: Exception) {
            logger?.invoke("Musixmatch title-fallback: failed (${errorLabel(e)})")
        }
        return@coroutineScope
    }

    // 4. Parallel fan-out with concurrency = 2
    val semaphore = Semaphore(SEARCH_CONCURRENCY)
    val terminalErrors = mutableListOf<Pair<Int, MusixmatchError>>()
    var queueStoppedLogged = false

    candidates.mapIndexed { index, candidate ->
        val rank = index + 1
        val contextLabel = "fan-out ranked $rank"
        launch {
            if (tokenState.terminalFailure != null) {
                if (!queueStoppedLogged) {
                    queueStoppedLogged = true
                    logger?.invoke("Musixmatch fan-out: queue stopped — terminal (${errorLabel(tokenState.terminalFailure!!)}) at ranked $rank")
                }
                return@launch
            }

            semaphore.withPermit {
                try {
                    val resolved = resolveRequest(
                        target = MacroTarget.ById(candidate.track.trackId, duration),
                        tokenState = tokenState,
                        verifyDuration = duration,
                        expectedTrackId = candidate.track.trackId,
                        contextLabel = contextLabel,
                    )
                    logger?.invoke("Musixmatch $contextLabel: success demerit=${candidate.demerit} quality=${resolved.quality} rating=${candidate.track.trackRating}")
                    callback(resolved.lyrics)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MusixmatchError.Instrumental) {
                    logger?.invoke("Musixmatch $contextLabel: instrumental")
                } catch (e: MusixmatchError.TokenInvalid) {
                    logger?.invoke("Musixmatch $contextLabel: failed (${errorLabel(e)})")
                    synchronized(terminalErrors) { terminalErrors.add(rank to e) }
                } catch (e: MusixmatchError.RateLimited) {
                    logger?.invoke("Musixmatch $contextLabel: failed (${errorLabel(e)})")
                    synchronized(terminalErrors) { terminalErrors.add(rank to e) }
                } catch (e: Exception) {
                    logger?.invoke("Musixmatch $contextLabel: failed (${errorLabel(e)})")
                }
            }
        }
    }.joinAll()

    // 5. Arbitrate terminal error if any occurred
    val terminalPair = synchronized(terminalErrors) { terminalErrors.minByOrNull { it.first } }
    if (terminalPair != null) {
        logger?.invoke("Musixmatch fan-out: final terminal (${errorLabel(terminalPair.second)}) from ranked ${terminalPair.first}")
        throw terminalPair.second
    }
}
