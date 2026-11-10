/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.musixmatch.models.ApiResponse
import moe.rukamori.archivetune.musixmatch.models.LyricsBody
import moe.rukamori.archivetune.musixmatch.models.MacroCalls
import moe.rukamori.archivetune.musixmatch.models.MacroResponseBody
import moe.rukamori.archivetune.musixmatch.models.MatcherBody
import moe.rukamori.archivetune.musixmatch.models.RichSyncBody
import moe.rukamori.archivetune.musixmatch.models.SearchBody
import moe.rukamori.archivetune.musixmatch.models.SubtitleBody
import moe.rukamori.archivetune.musixmatch.models.SubtitlesBody
import moe.rukamori.archivetune.musixmatch.models.TokenBody
import moe.rukamori.archivetune.musixmatch.models.Track
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import moe.rukamori.archivetune.musixmatch.models.bodyAs
import kotlin.math.abs

object Musixmatch {
    private const val SEARCH_PAGE_SIZE = 20
    private const val SEARCH_MAX_CANDIDATES = 4
    private const val SEARCH_CONCURRENCY = 2

    private val jsonFormat by lazy {
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    private val lyricsFormat by lazy { LyricsFormat(jsonFormat) }

    var logger: ((String) -> Unit)? = null

    internal fun createHttpClient(engine: HttpClientEngine? = null): HttpClient {
        val clientConfig: HttpClientConfig<*>.() -> Unit = {
            install(HttpTimeout) {
                requestTimeoutMillis = 20000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 20000
            }

            defaultRequest {
                headers.append("Accept", "application/json")
                headers.append("Accept-Language", "en-US,en;q=0.9")
                headers.append("X-Cookie", "x-mxm-token-guid=")
                headers.append("x-mxm-app-version", "10.1.1")
                headers.append("X-User-Agent", "Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0")
            }

            expectSuccess = false
        }

        return if (engine != null) HttpClient(engine, clientConfig) else HttpClient(OkHttp, clientConfig)
    }

    private val defaultClient: HttpClient by lazy { createHttpClient() }

    @Volatile
    private var overrideClient: HttpClient? = null

    internal var client: HttpClient
        get() = overrideClient ?: defaultClient
        set(value) {
            overrideClient = value
        }

    internal fun resetForTesting(engine: HttpClientEngine? = null) {
        cachedToken = null
        overrideClient = engine?.let { createHttpClient(it) }
    }

    private val tokenMutex = Mutex()

    @Volatile
    internal var cachedToken: String? = null

    internal suspend fun getToken(): String {
        cachedToken?.let { return it }
        return tokenMutex.withLock {
            cachedToken ?: fetchToken().also { cachedToken = it }
        }
    }

    internal suspend fun renewToken(previous: String): String =
        tokenMutex.withLock {
            val current = cachedToken
            if (current != null && current != previous) {
                current
            } else {
                fetchToken().also { cachedToken = it }
            }
        }

    private suspend fun fetchToken(): String {
        logger?.invoke("Musixmatch token request started")
        val response = client.get("${BASE_URL}token.get") { parameter("app_id", APP_ID) }
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.TooManyRequests) {
            throw MusixmatchError.RateLimited("HTTP ${response.status.value} on token.get")
        }
        if (response.status.value >= 500) throw MusixmatchError.ServerError(response.status.value)
        if (!response.status.isSuccess()) throw MusixmatchError.MalformedResponse("HTTP ${response.status.value} on token.get")

        val bodyText = response.bodyAsText()
        val apiResponse = runCatching { jsonFormat.decodeFromString<ApiResponse<TokenBody>>(bodyText) }
            .getOrElse { throw MusixmatchError.MalformedResponse("Failed to parse token response") }
        val statusCode = apiResponse.message?.header?.statusCode ?: 0
        if (statusCode == 401 || statusCode == 429) throw MusixmatchError.RateLimited("Status code $statusCode on token.get")
        if (statusCode >= 500) throw MusixmatchError.ServerError(statusCode)
        if (statusCode != 200) throw MusixmatchError.MalformedResponse("Status code $statusCode on token.get")

        val token = apiResponse.message?.body?.userToken
        if (token.isNullOrBlank() || token.all { it == '0' } || token.startsWith("UpgradeOnly")) {
            throw MusixmatchError.TokenInvalid("Token missing or blacklisted")
        }
        logger?.invoke("Musixmatch token acquired successfully")
        return token
    }

    internal suspend fun executeTrackSearch(
        title: String, artist: String, token: String, pathLabel: String,
    ): SearchBody {
        logger?.invoke("Musixmatch $pathLabel: track search request started")
        val response = client.get("${BASE_URL}track.search") {
            parameter("q_track", title)
            parameter("q_artist", artist)
            parameter("page_size", SEARCH_PAGE_SIZE)
            parameter("f_has_lyrics", 1)
            parameter("app_id", APP_ID)
            parameter("usertoken", token)
        }
        return decodeApiResponse(response, jsonFormat, "search")
    }

    internal suspend fun executeMacro(
        target: MacroTarget, token: String, contextLabel: String,
    ): MacroResponseBody {
        when (target) {
            is MacroTarget.ByName -> logger?.invoke("Musixmatch $contextLabel: macro request started (by-name)")
            is MacroTarget.ById -> logger?.invoke("Musixmatch $contextLabel: macro by-id request started")
        }
        val response = client.get("${BASE_URL}macro.subtitles.get") {
            parameter("format", "json")
            parameter("namespace", "lyrics_richsynched")
            parameter("subtitle_format", "mxm")
            parameter("app_id", APP_ID)
            parameter("optional_calls", "track.richsync")
            parameter("richsync_compact_type", "words")
            parameter("usertoken", token)

            when (target) {
                is MacroTarget.ByName -> {
                    parameter("q_track", target.title)
                    parameter("q_artist", target.artist)
                    if (!target.album.isNullOrBlank()) parameter("q_album", target.album)
                    if (target.durationSeconds > 0) {
                        parameter("q_duration", target.durationSeconds)
                        parameter("f_subtitle_length", target.durationSeconds)
                    }
                }
                is MacroTarget.ById -> parameter("track_id", target.trackId)
            }
        }
        return decodeApiResponse(response, jsonFormat, "macro")
    }

    internal fun matchedTrack(calls: MacroCalls?, expectedTrackId: Long? = null): Track {
        val matcherCall = calls?.matcherTrackGet
        val status = matcherCall?.message?.header?.statusCode ?: 0
        if (status != 200) throw MusixmatchError.NoMatch("Matcher status code is $status")
        val track = matcherCall.bodyAs<MatcherBody>(jsonFormat)?.track
            ?: throw MusixmatchError.NoMatch("No track matched")
        if (expectedTrackId != null && track.trackId != expectedTrackId) {
            throw MusixmatchError.Mismatch("Matcher track ID differs from requested ID")
        }
        return track
    }

    internal suspend fun resolveRequest(
        target: MacroTarget,
        tokenState: SearchTokenState,
        contextLabel: String,
    ): ResolvedLyrics {
        val calls = tokenState.execute { token -> executeMacro(target, token, contextLabel) }.macroCalls
        val track = when (target) {
            is MacroTarget.ByName -> {
                val matched = matchedTrack(calls)
                validateMatch(matched, target.title, target.artist, target.durationSeconds)
                matched
            }
            is MacroTarget.ById -> {
                val matched = matchedTrack(calls, target.trackId)
                if (target.title.isNotEmpty() || target.artist.isNotEmpty()) {
                    validateMatch(matched, target.title, target.artist, target.durationSeconds)
                } else if (target.durationSeconds > 0 && matched.trackLength > 0 &&
                    abs(matched.trackLength - target.durationSeconds) > DURATION_TOLERANCE
                ) {
                    throw MusixmatchError.Mismatch("Matcher metadata no longer matches candidate")
                }
                matched
            }
        }
        if (calls?.trackLyricsGet.bodyAs<LyricsBody>(jsonFormat)?.lyrics?.isRestricted == true) {
            throw MusixmatchError.Restricted()
        }
        if (track.isInstrumental) throw MusixmatchError.Instrumental()

        val inlineRichsync = if (calls?.trackRichsyncGet?.message?.header?.statusCode == 200) {
            calls.trackRichsyncGet.bodyAs<RichSyncBody>(jsonFormat)?.richsync?.richsyncBody
        } else null
        var enrichmentTerminal: MusixmatchError? = null
        val resolveEnrichment: suspend (suspend (String) -> String?, (String?) -> String?) -> String? = { fetch, tryFormat ->
            try {
                tokenState.execute { token -> fetch(token) }?.let { tryFormat(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MusixmatchError) {
                if (enrichmentTerminal == null && (e is MusixmatchError.TokenInvalid || e is MusixmatchError.RateLimited)) {
                    enrichmentTerminal = e
                }
                null
            } catch (e: Exception) {
                null
            }
        }
        val richsync = lyricsFormat.tryTtml(inlineRichsync) ?: if (track.isRichsyncAvailable) {
            resolveEnrichment({ fetchRichSyncSeparately(client, jsonFormat, track.commonTrackId, track.trackLength, it, contextLabel, logger) }, lyricsFormat::tryTtml)
        } else null
        richsync?.let { return ResolvedLyrics(it, 2, terminalError = enrichmentTerminal) }

        val inlineSubtitle = if (calls?.trackSubtitlesGet?.message?.header?.statusCode == 200) {
            calls.trackSubtitlesGet.bodyAs<SubtitlesBody>(jsonFormat)
                ?.subtitleList?.firstOrNull()?.subtitle?.subtitleBody
        } else null
        val subtitle = lyricsFormat.tryLrc(inlineSubtitle) ?: if (enrichmentTerminal == null && track.isSubtitlesAvailable) {
            resolveEnrichment({ fetchSubtitleSeparately(client, jsonFormat, track.trackId, track.trackLength, it, contextLabel, logger) }, lyricsFormat::tryLrc)
        } else null
        subtitle?.let { return ResolvedLyrics(it, 1, terminalError = enrichmentTerminal) }

        val plain = calls?.trackLyricsGet
            ?.takeIf { it.message.header.statusCode == 200 }
            .bodyAs<LyricsBody>(jsonFormat)
            ?.lyrics?.lyricsBody
        lyricsFormat.tryPlain(plain)?.let { return ResolvedLyrics(it, 0, terminalError = enrichmentTerminal) }

        if (enrichmentTerminal != null) throw enrichmentTerminal
        throw MusixmatchError.NoMatch("No lyrics found in any format")
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
    ): Result<String> {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val cleanAlbum = album?.trim().orEmpty()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) {
            return Result.failure(IllegalArgumentException("Song title and artist are required"))
        }
        return try {
            val tokenState = SearchTokenState(searchRequest { getToken() }, ::renewToken)
            val lyrics = try {
                try {
                    val res = resolveRequest(
                        MacroTarget.ByName(cleanTitle, cleanArtist, cleanAlbum, durationSeconds),
                        tokenState,
                        "playback",
                    )
                    if (res.terminalError != null) throw res.terminalError
                    logger?.invoke("Musixmatch playback: success quality=${res.quality}")
                    res.lyrics
                } catch (e: MusixmatchError.Instrumental) {
                    logger?.invoke("Musixmatch playback: instrumental")
                    "♪ Instrumental ♪"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger?.invoke("Musixmatch playback: failed (${errorLabel(e)})")
                if (e is MusixmatchError.NoMatch || e is MusixmatchError.Mismatch) {
                    resolveSearchFallback(tokenState, cleanTitle, cleanArtist, durationSeconds)
                } else {
                    throw e
                }
            }
            Result.success(lyrics)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
        callback: (String) -> Unit,
    ) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return

        val tokenState = SearchTokenState(searchRequest { getToken() }, ::renewToken)
        val searchBody = tokenState.execute { token ->
            executeTrackSearch(cleanTitle, cleanArtist, token, "fan-out")
        }
        val candidates = selectSearchCandidates(searchBody, cleanTitle, durationSeconds, SEARCH_MAX_CANDIDATES)
        logSearchFunnel("fan-out", searchBody, durationSeconds, candidates.size)

        if (candidates.isEmpty()) {
            val lyrics = try {
                logger?.invoke("Musixmatch title-fallback: title matcher")
                val res = resolveRequest(
                    MacroTarget.ByName(cleanTitle, cleanArtist, album?.trim().orEmpty(), durationSeconds),
                    tokenState,
                    "title-fallback",
                )
                if (res.terminalError != null) throw res.terminalError
                logger?.invoke("Musixmatch title-fallback: success quality=${res.quality}")
                res.lyrics
            } catch (e: CancellationException) {
                throw e
            } catch (e: MusixmatchError.Instrumental) {
                logger?.invoke("Musixmatch title-fallback: instrumental")
                null
            } catch (e: MusixmatchError) {
                logger?.invoke("Musixmatch title-fallback: failed (${errorLabel(e)})")
                if (e is MusixmatchError.TokenInvalid || e is MusixmatchError.RateLimited) throw e
                null
            }
            if (lyrics != null) {
                currentCoroutineContext().ensureActive()
                callback(lyrics)
            }
            return
        }

        val startedCount = AtomicInteger(0)
        val queueStopped = AtomicBoolean(false)
        val terminalErrors = ConcurrentLinkedQueue<Pair<Int, MusixmatchError>>()
        val recordTerminal = { rank: Int, error: MusixmatchError ->
            terminalErrors.add(rank to error)
            if (startedCount.get() < candidates.size && queueStopped.compareAndSet(false, true)) {
                logger?.invoke("Musixmatch fan-out: queue stopped — terminal (${errorLabel(error)}) at ranked $rank")
            }
        }
        val semaphore = Semaphore(SEARCH_CONCURRENCY)
        val callbackMutex = Mutex()
        coroutineScope {
            val searchScope = this
            val jobs = candidates.mapIndexed { index, candidate ->
                launch {
                    val rank = index + 1
                    if (terminalErrors.isNotEmpty() || tokenState.isRenewalFailed) return@launch

                    val lyrics = semaphore.withPermit {
                        currentCoroutineContext().ensureActive()
                        if (terminalErrors.isNotEmpty() || tokenState.isRenewalFailed) return@withPermit null
                        startedCount.incrementAndGet()
                        try {
                            val res = resolveRequest(
                                MacroTarget.ById(candidate.track.trackId, durationSeconds = durationSeconds),
                                tokenState,
                                "fan-out ranked $rank",
                            )
                            logger?.invoke("Musixmatch fan-out ranked $rank: success demerit=${candidate.demerit} quality=${res.quality} rating=${candidate.track.trackRating}")
                            if (res.terminalError != null) recordTerminal(rank, res.terminalError)
                            res.lyrics
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: MusixmatchError.Instrumental) {
                            logger?.invoke("Musixmatch fan-out ranked $rank: instrumental")
                            null
                        } catch (e: Exception) {
                            logger?.invoke("Musixmatch fan-out ranked $rank: failed (${errorLabel(e)})")
                            if (e is MusixmatchError.TokenInvalid || e is MusixmatchError.RateLimited) recordTerminal(rank, e)
                            null
                        }
                    }
                    if (lyrics != null) {
                        callbackMutex.withLock {
                            try {
                                callback(lyrics)
                            } catch (e: CancellationException) {
                                searchScope.cancel(e)
                                throw e
                            }
                        }
                    }
                }
            }
            jobs.joinAll()
        }

        val bestTerminal = terminalErrors.minByOrNull { it.first }
        if (bestTerminal != null) {
            logger?.invoke("Musixmatch fan-out: final terminal (${errorLabel(bestTerminal.second)}) from ranked ${bestTerminal.first}")
            throw bestTerminal.second
        }
    }
}
