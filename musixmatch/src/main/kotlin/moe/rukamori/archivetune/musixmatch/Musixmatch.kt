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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.musixmatch.models.MacroBody
import moe.rukamori.archivetune.musixmatch.models.MacroCalls
import moe.rukamori.archivetune.musixmatch.models.MusixmatchResponse
import moe.rukamori.archivetune.musixmatch.models.RichSyncBody
import moe.rukamori.archivetune.musixmatch.models.SubtitleBody
import moe.rukamori.archivetune.musixmatch.models.Track
import moe.rukamori.archivetune.musixmatch.models.TrackSearchBody
import moe.rukamori.archivetune.musixmatch.models.UserTokenBody

object Musixmatch {
    internal const val BASE_URL = "https://apic-appmobile.musixmatch.com/ws/1.1/"
    internal const val APP_ID = "mac-ios-v2.0"
    internal const val SEARCH_PAGE_SIZE = 20

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

    internal suspend fun renewToken(previous: String? = null): String =
        tokenMutex.withLock {
            val current = cachedToken
            if (current != null && previous != null && current != previous) {
                current
            } else {
                fetchToken().also { cachedToken = it }
            }
        }

    private suspend fun fetchToken(): String {
        logger?.invoke("Musixmatch token request started")
        val response = client.get("${BASE_URL}token.get") {
            parameter("app_id", APP_ID)
        }

        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.TooManyRequests) {
            throw MusixmatchError.RateLimited("HTTP ${response.status.value} on token.get")
        }
        if (response.status.value >= 500) {
            throw MusixmatchError.ServerError(response.status.value)
        }
        if (!response.status.isSuccess()) {
            throw MusixmatchError.MalformedResponse("HTTP ${response.status.value} on token.get")
        }

        val bodyText = response.bodyAsText()
        val apiResponse = runCatching {
            jsonFormat.decodeFromString<MusixmatchResponse<UserTokenBody>>(bodyText)
        }.getOrElse {
            throw MusixmatchError.MalformedResponse("Failed to parse token response", it)
        }

        val statusCode = apiResponse.message.header.statusCode
        if (statusCode == 401 || statusCode == 429) {
            throw MusixmatchError.RateLimited("Status code $statusCode on token.get")
        }
        if (statusCode >= 500) {
            throw MusixmatchError.ServerError(statusCode)
        }
        if (statusCode != 200) {
            throw MusixmatchError.MalformedResponse("Status code $statusCode on token.get")
        }

        val token = apiResponse.message.body?.userToken
        if (token.isNullOrBlank() || token.all { it == '0' } || token.startsWith("UpgradeOnly")) {
            throw MusixmatchError.TokenInvalid("Token missing or blacklisted")
        }

        logger?.invoke("Musixmatch token acquired successfully")
        return token
    }

    internal suspend inline fun <reified T> decodeApiResponse(
        response: HttpResponse,
        operation: String,
    ): T {
        if (response.status == HttpStatusCode.Unauthorized) {
            throw MusixmatchError.TokenInvalid("HTTP 401 on $operation")
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw MusixmatchError.RateLimited("HTTP 429 on $operation")
        }
        if (response.status.value >= 500) {
            throw MusixmatchError.ServerError(response.status.value)
        }
        if (!response.status.isSuccess()) {
            throw MusixmatchError.MalformedResponse("HTTP ${response.status.value} on $operation")
        }

        val bodyText = response.bodyAsText()
        val apiResponse = runCatching {
            jsonFormat.decodeFromString<MusixmatchResponse<T>>(bodyText)
        }.getOrElse {
            throw MusixmatchError.MalformedResponse("Failed to parse $operation response", it)
        }

        val statusCode = apiResponse.message.header.statusCode
        if (statusCode == 401) throw MusixmatchError.TokenInvalid("Status code 401 on $operation")
        if (statusCode == 429) throw MusixmatchError.RateLimited("Status code 429 on $operation")
        if (statusCode >= 500) throw MusixmatchError.ServerError(statusCode)
        if (statusCode != 200) throw MusixmatchError.MalformedResponse("Status code $statusCode on $operation")

        return apiResponse.message.body ?: throw MusixmatchError.MalformedResponse("Empty $operation body")
    }

    internal suspend fun executeMacro(
        target: MacroTarget,
        token: String,
        contextLabel: String = "playback",
    ): MacroBody {
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
                    if (!target.album.isNullOrBlank()) {
                        parameter("q_album", target.album)
                    }
                    if (target.durationSeconds > 0) {
                        parameter("q_duration", target.durationSeconds)
                        parameter("f_subtitle_length", target.durationSeconds)
                    }
                }
                is MacroTarget.ById -> {
                    parameter("track_id", target.trackId)
                    if (target.durationSeconds > 0) {
                        parameter("f_subtitle_length", target.durationSeconds)
                    }
                }
            }
        }

        return decodeApiResponse(response, "macro.subtitles.get")
    }

    internal suspend fun fetchRichSyncSeparately(
        commontrackId: Long,
        durationSeconds: Int,
        token: String,
        contextLabel: String = "playback",
    ): String? {
        if (commontrackId <= 0) return null
        return try {
            logger?.invoke("Musixmatch $contextLabel: separate richsync request started")
            val response = client.get("${BASE_URL}track.richsync.get") {
                parameter("format", "json")
                parameter("subtitle_format", "mxm")
                parameter("app_id", APP_ID)
                parameter("richsync_compact_type", "words")
                parameter("commontrack_id", commontrackId)
                if (durationSeconds > 0) {
                    parameter("f_subtitle_length", durationSeconds)
                    parameter("q_duration", durationSeconds)
                }
                parameter("usertoken", token)
            }
            if (response.status == HttpStatusCode.Unauthorized) throw MusixmatchError.TokenInvalid("HTTP 401 on richsync")
            if (response.status == HttpStatusCode.TooManyRequests) throw MusixmatchError.RateLimited("HTTP 429 on richsync")
            if (!response.status.isSuccess()) return null

            val body = jsonFormat.decodeFromString<MusixmatchResponse<RichSyncBody>>(response.bodyAsText())
            val status = body.message.header.statusCode
            if (status == 401) throw MusixmatchError.TokenInvalid("Status code 401 on richsync")
            if (status == 429) throw MusixmatchError.RateLimited("Status code 429 on richsync")
            if (status != 200) return null
            body.message.body?.richsync?.richsyncBody
        } catch (e: CancellationException) {
            throw e
        } catch (e: MusixmatchError.TokenInvalid) {
            throw e
        } catch (e: MusixmatchError.RateLimited) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    internal suspend fun fetchSubtitleSeparately(
        trackId: Long,
        token: String,
        contextLabel: String = "playback",
    ): String? {
        if (trackId <= 0) return null
        return try {
            logger?.invoke("Musixmatch $contextLabel: separate subtitle request started")
            val response = client.get("${BASE_URL}track.subtitle.get") {
                parameter("format", "json")
                parameter("subtitle_format", "lrc")
                parameter("app_id", APP_ID)
                parameter("track_id", trackId)
                parameter("usertoken", token)
            }
            if (response.status == HttpStatusCode.Unauthorized) throw MusixmatchError.TokenInvalid("HTTP 401 on subtitle")
            if (response.status == HttpStatusCode.TooManyRequests) throw MusixmatchError.RateLimited("HTTP 429 on subtitle")
            if (!response.status.isSuccess()) return null

            val body = jsonFormat.decodeFromString<MusixmatchResponse<SubtitleBody>>(response.bodyAsText())
            val status = body.message.header.statusCode
            if (status == 401) throw MusixmatchError.TokenInvalid("Status code 401 on subtitle")
            if (status == 429) throw MusixmatchError.RateLimited("Status code 429 on subtitle")
            if (status != 200) return null
            body.message.body?.subtitle?.subtitleBody
        } catch (e: CancellationException) {
            throw e
        } catch (e: MusixmatchError.TokenInvalid) {
            throw e
        } catch (e: MusixmatchError.RateLimited) {
            throw e
        } catch (e: Exception) {
            null
        }
    }


    internal suspend fun resolveRequest(
        target: MacroTarget,
        tokenState: SearchTokenState,
        verifyTitle: String? = null,
        verifyArtist: String? = null,
        verifyDuration: Int = 0,
        expectedTrackId: Long? = null,
        contextLabel: String = "playback",
    ): ResolvedLyrics {
        val macro = tokenState.execute { token -> executeMacro(target, token, contextLabel) }
        val track = matchedTrack(macro.macroCalls, expectedTrackId)

        validateMatch(
            track = track,
            inputTitle = verifyTitle,
            inputArtist = verifyArtist,
            inputDuration = verifyDuration,
        )

        if (track.restricted == 1) throw MusixmatchError.Restricted()
        if (track.instrumental == 1) throw MusixmatchError.Instrumental()

        return resolveTier(
            quality = 2,
            inlineBody = macro.macroCalls.richsync?.message?.body?.richsync?.richsyncBody,
            hasStandalone = track.hasRichsync == 1,
            convert = lyricsFormat::tryTtml,
            fetchStandalone = {
                val commonId = track.commontrackId ?: track.trackId
                tokenState.execute { token -> fetchRichSyncSeparately(commonId, track.trackLength, token, contextLabel) }
            },
        ) ?: resolveTier(
            quality = 1,
            inlineBody = macro.macroCalls.subtitles?.message?.body?.subtitleList?.firstOrNull()?.subtitle?.subtitleBody,
            hasStandalone = track.hasSubtitles == 1,
            convert = lyricsFormat::tryLrc,
            fetchStandalone = {
                tokenState.execute { token -> fetchSubtitleSeparately(track.trackId, token, contextLabel) }
            },
        ) ?: resolveTier(
            quality = 0,
            inlineBody = macro.macroCalls.lyrics?.message?.body?.lyrics?.lyricsBody,
            convert = lyricsFormat::tryPlain,
        ) ?: throw MusixmatchError.NoMatch("No usable lyrics found in any format")
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
    ): Result<String> = runCatching {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val cleanAlbum = album?.trim().orEmpty()

        if (cleanTitle.isEmpty() || cleanArtist.isEmpty()) {
            throw MusixmatchError.NoMatch("Title and artist must not be empty")
        }

        val duration = durationSeconds.coerceAtLeast(0)
        val tokenState = SearchTokenState(getToken()) { renewToken() }

        // 1. Primary Matcher by-name
        try {
            val resolved = resolveRequest(
                target = MacroTarget.ByName(cleanTitle, cleanArtist, cleanAlbum, duration),
                tokenState = tokenState,
                verifyTitle = cleanTitle,
                verifyArtist = cleanArtist,
                verifyDuration = duration,
                contextLabel = "playback",
            )
            logger?.invoke("Musixmatch playback: success quality=${resolved.quality}")
            return@runCatching resolved.lyrics
        } catch (e: CancellationException) {
            throw e
        } catch (e: MusixmatchError.Instrumental) {
            logger?.invoke("Musixmatch playback: instrumental")
            return@runCatching "♪ Instrumental ♪"
        } catch (e: MusixmatchError.NoMatch) {
            logger?.invoke("Musixmatch playback: failed (${errorLabel(e)})")
        } catch (e: MusixmatchError.Mismatch) {
            logger?.invoke("Musixmatch playback: failed (${errorLabel(e)})")
        } catch (e: Exception) {
            logger?.invoke("Musixmatch playback: failed (${errorLabel(e)})")
            throw e
        }

        // 2. Fallback Search directly in getLyrics
        logger?.invoke("Musixmatch fallback: ranked search")
        val searchBody = tokenState.execute { token -> executeTrackSearch(cleanTitle, cleanArtist, token, "fallback") }
        val candidates = selectSearchCandidates(
            body = searchBody,
            inputTitle = cleanTitle,
            durationSeconds = duration,
            limit = SEARCH_FALLBACK_MAX_ATTEMPTS,
        )
        logSearchFunnel("fallback", searchBody, duration, candidates.size)
        if (candidates.isEmpty()) throw MusixmatchError.NoMatch("No search candidates matched")

        coroutineScope {
            val outcomes = candidates.mapIndexed { index, candidate ->
                val rank = index + 1
                val contextLabel = "fallback ranked $rank"
                async {
                    try {
                        val resolved = resolveRequest(
                            target = MacroTarget.ById(candidate.track.trackId, duration),
                            tokenState = tokenState,
                            verifyTitle = cleanTitle,
                            verifyArtist = cleanArtist,
                            verifyDuration = duration,
                            expectedTrackId = candidate.track.trackId,
                            contextLabel = contextLabel,
                        )
                        logger?.invoke("Musixmatch $contextLabel: success demerit=${candidate.demerit} quality=${resolved.quality} rating=${candidate.track.trackRating}")
                        CandidateOutcome.Success(candidate, resolved)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: MusixmatchError.Instrumental) {
                        logger?.invoke("Musixmatch $contextLabel: instrumental")
                        CandidateOutcome.LocalFailure(candidate, e)
                    } catch (e: MusixmatchError.TokenInvalid) {
                        logger?.invoke("Musixmatch $contextLabel: failed (${errorLabel(e)})")
                        CandidateOutcome.Terminal(candidate, e)
                    } catch (e: MusixmatchError.RateLimited) {
                        logger?.invoke("Musixmatch $contextLabel: failed (${errorLabel(e)})")
                        CandidateOutcome.Terminal(candidate, e)
                    } catch (e: Exception) {
                        logger?.invoke("Musixmatch $contextLabel: failed (${errorLabel(e)})")
                        CandidateOutcome.LocalFailure(candidate, e)
                    }
                }
            }.awaitAll()

            val successes = outcomes.filterIsInstance<CandidateOutcome.Success>()
            if (successes.isNotEmpty()) {
                val winner = successes.minWith(
                    compareBy<CandidateOutcome.Success> { it.candidate.demerit }
                        .thenByDescending { it.resolved.quality }
                        .thenByDescending { it.candidate.track.trackRating }
                )
                val winnerRank = candidates.indexOfFirst { it.track.trackId == winner.candidate.track.trackId } + 1
                logger?.invoke("Musixmatch fallback: winner from ranked $winnerRank")
                return@coroutineScope winner.resolved.lyrics
            }

            // Error adjudication if no successes
            val terminal = outcomes.filterIsInstance<CandidateOutcome.Terminal>().firstOrNull()
            if (terminal != null) {
                val terminalRank = candidates.indexOfFirst { it.track.trackId == terminal.candidate.track.trackId } + 1
                logger?.invoke("Musixmatch fallback: final terminal (${errorLabel(terminal.error)}) from ranked $terminalRank")
                throw terminal.error
            }

            val firstFailure = outcomes.filterIsInstance<CandidateOutcome.LocalFailure>().firstOrNull()
            val failedRank = firstFailure?.let { candidates.indexOfFirst { c -> c.track.trackId == it.candidate.track.trackId } + 1 } ?: 1
            val errorToThrow = firstFailure?.error ?: MusixmatchError.NoMatch("Search candidates produced no usable lyrics")
            logger?.invoke("Musixmatch fallback: all candidates failed (${errorLabel(errorToThrow)}) from ranked $failedRank")
            throw errorToThrow
        }
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
        callback: (String) -> Unit,
    ) = executeSearchGetAllLyrics(title, artist, album, durationSeconds, callback)
}
