/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MusixmatchHybridTest {
    private val requests = mutableListOf<Url>()
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    @After
    fun tearDown() {
        Musixmatch.resetForTesting()
        Musixmatch.logger = null
    }

    private fun setup(
        tracks: List<String> = emptyList(),
        titleMacro: String = macro(),
        idMacros: Map<Long, String> = emptyMap(),
        titleStatus: Int = 200,
        searchStatus: Int = 200,
        idStatuses: Map<Long, Int> = emptyMap(),
        renewalStatus: Int = 200,
    ) {
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(envelope("{\"track_list\":[${tracks.joinToString(",") { "{\"track\":$it}" }}]}", searchStatus), headers = headers)
                "macro.subtitles.get" -> {
                    val id = request.url.parameters["track_id"]?.toLongOrNull()
                    if (id == null) respond(titleMacro, HttpStatusCode.fromValue(titleStatus), headers)
                    else respond(idMacros[id] ?: macro(id), HttpStatusCode.fromValue(idStatuses[id] ?: 200), headers)
                }
                "token.get" -> respond(envelope("{\"user_token\":\"renewed_test_token\"}", renewalStatus), headers = headers)
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
    }

    private suspend fun search(duration: Int = 100): List<String> {
        val results = mutableListOf<String>()
        Musixmatch.getAllLyrics(" Koiiro ", " Mosawo ", " Album ", duration, results::add)
        return results
    }

    private suspend fun playback() = Musixmatch.getLyrics("Koiiro", "Mosawo", "Album", 100)
    private fun ids() = requests.filter { it.encodedPath.endsWith("macro.subtitles.get") }.mapNotNull { it.parameters["track_id"]?.toLongOrNull() }
    private fun titles() = requests.filter { it.encodedPath.endsWith("macro.subtitles.get") && it.parameters["track_id"] == null }
    private fun searches() = requests.count { it.encodedPath.endsWith("track.search") }

    @Test
    fun emptySearchFallsBackWithAlbumDurationAndDifferentScriptArtist() = runBlocking {
        setup(titleMacro = macro(track = track(1, artist = "もさを。")))
        assertEquals(listOf("Lyrics 1"), search())
        val params = titles().single().parameters
        assertEquals("Koiiro", params["q_track"])
        assertEquals("Mosawo", params["q_artist"])
        assertEquals("Album", params["q_album"])
        assertEquals("100", params["q_duration"])
        assertEquals("100", params["f_subtitle_length"])
        assertEquals(1, searches())
    }

    @Test
    fun entirelyFilteredPoolStillTriesTitleMatcher() = runBlocking {
        setup(tracks = listOf(track(0), track(2, length = 109), track(3, instrumental = 1)))
        assertEquals(listOf("Lyrics 1"), search())
        assertEquals(1, titles().size)
        assertTrue(ids().isEmpty())
    }

    @Test
    fun emptySearchUnusableMatcherEmitsNothing() = runBlocking {
        for (body in listOf(
            macro(matcherStatus = 404),
            macro(track = track(1, length = 109)),
            macro(track = track(1, title = "Other", artist = "Different")),
            macro(track = track(1, instrumental = 1)),
            macro(restricted = true),
        )) {
            setup(titleMacro = body)
            assertEquals(emptyList<String>(), search())
            assertEquals(1, titles().size)
        }
    }

    @Test
    fun nonemptyPoolDoesNotTryTitleMatcherEvenIfAllCandidatesFail() = runBlocking {
        setup(tracks = listOf(track(1)), idMacros = mapOf(1L to macro(matcherStatus = 404)))
        assertEquals(emptyList<String>(), search())
        assertTrue(titles().isEmpty())
    }

    @Test
    fun emptySearchMatcherRateLimitIsTerminal() = runBlocking {
        setup(titleStatus = 429)
        val failure = try { search(); null } catch (e: MusixmatchError) { e }
        assertTrue(failure is MusixmatchError.RateLimited)
        assertEquals(1, titles().size)
        assertEquals(2, requests.size)
    }

    @Test
    fun noMatchAndMismatchTriggerPlaybackSearchFallback() = runBlocking {
        for (body in listOf(macro(matcherStatus = 404), macro(track = track(1, length = 109)))) {
            setup(tracks = listOf(track(2)), titleMacro = body)
            assertEquals("Lyrics 2", playback().getOrThrow())
            assertEquals(listOf(2L), ids())
            assertEquals(1, searches())
        }
    }

    @Test
    fun playbackAttemptsCandidatesInParallelAndPicksBest() = runBlocking {
        setup(tracks = listOf(track(2), track(3)), titleMacro = macro(matcherStatus = 404))
        assertEquals("Lyrics 2", playback().getOrThrow())
        assertEquals(listOf(2L, 3L), ids())
    }

    @Test
    fun playbackAttemptsAtMostTwoCandidatesInParallel() = runBlocking {
        setup(tracks = listOf(track(2), track(3), track(4)), titleMacro = macro(matcherStatus = 404), idMacros = mapOf(2L to macro(2, matcherStatus = 404)))
        assertEquals("Lyrics 3", playback().getOrThrow())
        assertEquals(listOf(2L, 3L), ids())
        setup(tracks = listOf(track(2), track(3), track(4)), titleMacro = macro(matcherStatus = 404), idMacros = mapOf(2L to macro(2, matcherStatus = 404), 3L to macro(3, matcherStatus = 404)))
        assertTrue(playback().exceptionOrNull() is MusixmatchError.NoMatch)
        assertEquals(listOf(2L, 3L), ids())
    }

    @Test
    fun fallbackPlaybackRequiresSameIdTextAndDurationAndSkipsInstrumental() = runBlocking {
        for (body in listOf(
            macro(99),
            macro(2, track = track(2, title = "Other", artist = "Different")),
            macro(2, track = track(2, length = 109)),
            macro(2, track = track(2, instrumental = 1)),
        )) {
            setup(tracks = listOf(track(2), track(3)), titleMacro = macro(matcherStatus = 404), idMacros = mapOf(2L to body))
            assertEquals("Lyrics 3", playback().getOrThrow())
            assertEquals(listOf(2L, 3L), ids())
        }
    }

    @Test
    fun restrictedFallbackCandidateIsSkippedButAllRestrictedReturnsRestricted() = runBlocking {
        setup(tracks = listOf(track(2), track(3)), titleMacro = macro(matcherStatus = 404), idMacros = mapOf(2L to macro(2, restricted = true)))
        assertEquals("Lyrics 3", playback().getOrThrow())
        setup(tracks = listOf(track(2), track(3)), titleMacro = macro(matcherStatus = 404), idMacros = mapOf(2L to macro(2, restricted = true), 3L to macro(3, restricted = true)))
        assertTrue(playback().exceptionOrNull() is MusixmatchError.Restricted)
    }

    @Test
    fun fallbackPlaybackReturnsBestRankedCandidateError() = runBlocking {
        setup(tracks = listOf(track(2), track(3)), titleMacro = macro(matcherStatus = 404), idMacros = mapOf(2L to macro(2, restricted = true), 3L to macro(99)))
        assertTrue(playback().exceptionOrNull() is MusixmatchError.Restricted)
    }

    @Test
    fun candidateServerAndMalformedResponsesAllowSecondAttempt() = runBlocking {
        for ((body, status) in listOf("not json" to 200, macro(2) to 500)) {
            setup(tracks = listOf(track(2), track(3)), titleMacro = macro(matcherStatus = 404), idMacros = mapOf(2L to body), idStatuses = mapOf(2L to status))
            assertEquals("Lyrics 3", playback().getOrThrow())
        }
    }

    @Test
    fun primarySuccessRestrictedServerMalformedAndRateLimitDoNotSearch() = runBlocking {
        for ((body, status) in listOf(macro() to 200, macro(restricted = true) to 200, macro() to 500, "bad json" to 200, macro() to 429)) {
            setup(titleMacro = body, titleStatus = status)
            val result = playback()
            if (body == macro() && status == 200) assertEquals("Lyrics 1", result.getOrThrow())
            else assertTrue(result.isFailure)
            assertEquals(0, searches())
        }
    }

    @Test
    fun fallbackRateLimitYieldsToConcurrentSuccessfulCandidate() = runBlocking {
        val candidate3Entered = CompletableDeferred<Unit>()
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(envelope("{\"track_list\":[${listOf(track(2), track(3)).joinToString(",") { "{\"track\":$it}" }}]}"), headers = headers)
                "macro.subtitles.get" -> {
                    val id = request.url.parameters["track_id"]?.toLongOrNull()
                    if (id == null) {
                        respond(macro(matcherStatus = 404), headers = headers)
                    } else if (id == 2L) {
                        candidate3Entered.await()
                        respond(macro(2L), HttpStatusCode.TooManyRequests, headers)
                    } else {
                        candidate3Entered.complete(Unit)
                        respond(macro(3L), headers = headers)
                    }
                }
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
        assertEquals("Lyrics 3", playback().getOrThrow())
        assertEquals(listOf(2L, 3L), ids())
    }

    @Test
    fun fallbackAllCandidatesTerminalReturnsBestRankedTerminal() = runBlocking {
        val candidate3Entered = CompletableDeferred<Unit>()
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(envelope("{\"track_list\":[${listOf(track(2), track(3)).joinToString(",") { "{\"track\":$it}" }}]}"), headers = headers)
                "macro.subtitles.get" -> {
                    val id = request.url.parameters["track_id"]?.toLongOrNull()
                    if (id == null) {
                        respond(macro(matcherStatus = 404), headers = headers)
                    } else if (id == 2L) {
                        candidate3Entered.await()
                        respond(macro(2L), HttpStatusCode.TooManyRequests, headers)
                    } else {
                        candidate3Entered.complete(Unit)
                        respond(macro(3L), HttpStatusCode.TooManyRequests, headers)
                    }
                }
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
        val failure = playback().exceptionOrNull()
        assertTrue(failure is MusixmatchError.RateLimited)
        assertEquals(listOf(2L, 3L), ids())
    }

    @Test
    fun fallbackFailedRenewalWhenAllCandidatesUnauthorizedReturnsTokenInvalid() = runBlocking {
        setup(tracks = listOf(track(2), track(3)), titleMacro = macro(matcherStatus = 404), idStatuses = mapOf(2L to 401, 3L to 401), renewalStatus = 500)
        val failure = playback().exceptionOrNull()
        assertTrue(failure is MusixmatchError.TokenInvalid)
    }

    @Test
    fun fallbackUnauthorizedCandidateYieldsToConcurrentSuccessfulCandidate() = runBlocking {
        setup(tracks = listOf(track(2), track(3)), titleMacro = macro(matcherStatus = 404), idStatuses = mapOf(2L to 401), renewalStatus = 500)
        assertEquals("Lyrics 3", playback().getOrThrow())
        assertEquals(listOf(2L, 3L), ids())
    }

    @Test
    fun renewalBudgetIsSharedAcrossPrimarySearchAndIdMacro() = runBlocking {
        requests.clear()
        var titleCalls = 0
        var renewals = 0
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "token.get" -> { renewals++; respond(envelope("{\"user_token\":\"renewed_test_token\"}"), headers = headers) }
                "track.search" -> respond(envelope("{\"track_list\":[{\"track\":${track(2)}}]}"), headers = headers)
                "macro.subtitles.get" -> if (request.url.parameters["track_id"] == null) {
                    titleCalls++
                    if (titleCalls == 1) respond(envelope("{}", 401), headers = headers)
                    else respond(macro(matcherStatus = 404), headers = headers)
                } else respond(envelope("{}", 401), headers = headers)
                else -> error("Unexpected endpoint")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
        assertTrue(playback().exceptionOrNull() is MusixmatchError.TokenInvalid)
        assertEquals(1, renewals)
        assertEquals(listOf(2L), ids())
    }

    @Test
    fun callbackCancellationOnTitleFallbackPropagates() = runBlocking {
        setup()
        val cancellation = CancellationException("consumer stopped")
        val thrown = try {
            Musixmatch.getAllLyrics("Koiiro", "Mosawo", durationSeconds = 100) { throw cancellation }
            null
        } catch (e: CancellationException) { e }
        assertSame(cancellation, thrown)
    }

    @Test
    fun titleFallbackCallbackThrowsMusixmatchError_propagatesException() = runBlocking {
        setup()
        val error = MusixmatchError.NoMatch("from consumer")
        val thrown = try {
            Musixmatch.getAllLyrics("Koiiro", "Mosawo", durationSeconds = 100) { throw error }
            null
        } catch (e: MusixmatchError) { e }
        assertSame(error, thrown)
    }

    @Test
    fun fanOutRank1RateLimited_rank2RichsyncEnrichmentPreservesPlainLyricsAndThrowsTerminal() = runBlocking {
        val candidate2Entered = CompletableDeferred<Unit>()
        val results = mutableListOf<String>()
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(
                    envelope("{\"track_list\":[{\"track\":${track(1)}},{\"track\":${track(2, richsync = 1)}}]}"),
                    headers = headers,
                )
                "macro.subtitles.get" -> {
                    val id = request.url.parameters["track_id"]?.toLongOrNull()
                    if (id == 1L) {
                        candidate2Entered.await()
                        respond(macro(1L), HttpStatusCode.TooManyRequests, headers)
                    } else {
                        respond(macro(2L, track = track(2, richsync = 1)), headers = headers)
                    }
                }
                "track.richsync.get" -> {
                    candidate2Entered.complete(Unit)
                    respond(envelope("{}", 429), HttpStatusCode.TooManyRequests, headers = headers)
                }
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
        val failure = try {
            Musixmatch.getAllLyrics("Koiiro", "Mosawo", durationSeconds = 100) { results.add(it) }
            null
        } catch (e: MusixmatchError) { e }
        assertTrue(failure is MusixmatchError.RateLimited)
        assertEquals(listOf("Lyrics 2"), results)
        assertTrue(requests.any { it.encodedPath.endsWith("track.richsync.get") })
    }

    @Test
    fun playbackFallbackRank1RateLimited_rank2RichsyncEnrichmentPreservesPlainLyricsAndReturnsSuccess() = runBlocking {
        val candidate2Entered = CompletableDeferred<Unit>()
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(
                    envelope("{\"track_list\":[{\"track\":${track(2)}},{\"track\":${track(3, richsync = 1)}}]}"),
                    headers = headers,
                )
                "macro.subtitles.get" -> {
                    val id = request.url.parameters["track_id"]?.toLongOrNull()
                    if (id == null) {
                        respond(macro(matcherStatus = 404), headers = headers)
                    } else if (id == 2L) {
                        candidate2Entered.await()
                        respond(macro(2L), HttpStatusCode.TooManyRequests, headers)
                    } else {
                        respond(macro(3L, track = track(3, richsync = 1)), headers = headers)
                    }
                }
                "track.richsync.get" -> {
                    candidate2Entered.complete(Unit)
                    respond(envelope("{}", 429), HttpStatusCode.TooManyRequests, headers = headers)
                }
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
        val result = playback()
        assertEquals("Lyrics 3", result.getOrThrow())
        assertTrue(requests.any { it.encodedPath.endsWith("track.richsync.get") })
    }

    @Test
    fun fanOutSingleCandidate_richsyncEnrichmentRateLimited_yieldsPlainAndThrowsRateLimited() = runBlocking {
        val results = mutableListOf<String>()
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(
                    envelope("{\"track_list\":[{\"track\":${track(1, richsync = 1)}}]}"),
                    headers = headers,
                )
                "macro.subtitles.get" -> respond(macro(1L, track = track(1, richsync = 1)), headers = headers)
                "track.richsync.get" -> respond(envelope("{}", 429), HttpStatusCode.TooManyRequests, headers = headers)
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
        val failure = try {
            Musixmatch.getAllLyrics("Koiiro", "Mosawo", durationSeconds = 100) { results.add(it) }
            null
        } catch (e: MusixmatchError) { e }
        assertTrue(failure is MusixmatchError.RateLimited)
        assertEquals(listOf("Lyrics 1"), results)
        assertTrue(requests.any { it.encodedPath.endsWith("track.richsync.get") })
    }

    @Test
    fun fanOutSingleCandidate_richsyncEnrichmentTokenRenewalFails_yieldsPlainAndThrowsTokenInvalid() = runBlocking {
        val results = mutableListOf<String>()
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(
                    envelope("{\"track_list\":[{\"track\":${track(1, richsync = 1)}}]}"),
                    headers = headers,
                )
                "macro.subtitles.get" -> respond(macro(1L, track = track(1, richsync = 1)), headers = headers)
                "track.richsync.get" -> respond(envelope("{}", 401), HttpStatusCode.Unauthorized, headers = headers)
                "token.get" -> respond("Internal Server Error", HttpStatusCode.InternalServerError, headers = headers)
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
        val failure = try {
            Musixmatch.getAllLyrics("Koiiro", "Mosawo", durationSeconds = 100) { results.add(it) }
            null
        } catch (e: MusixmatchError) { e }
        assertTrue(failure is MusixmatchError.TokenInvalid)
        assertEquals(listOf("Lyrics 1"), results)
        assertTrue(requests.any { it.encodedPath.endsWith("track.richsync.get") })
        assertTrue(requests.any { it.encodedPath.endsWith("token.get") })
    }

    @Test
    fun fanOutSingleCandidate_subtitleEnrichmentRateLimited_yieldsPlainAndThrowsRateLimited() = runBlocking {
        val results = mutableListOf<String>()
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(
                    envelope("{\"track_list\":[{\"track\":${track(1, sub = 1)}}]}"),
                    headers = headers,
                )
                "macro.subtitles.get" -> respond(macro(1L, track = track(1, sub = 1)), headers = headers)
                "track.subtitle.get" -> respond(envelope("{}", 429), HttpStatusCode.TooManyRequests, headers = headers)
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
        val failure = try {
            Musixmatch.getAllLyrics("Koiiro", "Mosawo", durationSeconds = 100) { results.add(it) }
            null
        } catch (e: MusixmatchError) { e }
        assertTrue(failure is MusixmatchError.RateLimited)
        assertEquals(listOf("Lyrics 1"), results)
        assertTrue(requests.any { it.encodedPath.endsWith("track.subtitle.get") })
    }

    @Test
    fun fanOutSearchRenewalSuccess_allFourCandidatesExecutedSuccessfully() = runBlocking {
        val results = mutableListOf<String>()
        requests.clear()
        var searchAttempts = 0
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> {
                    searchAttempts++
                    if (searchAttempts == 1) {
                        respond(envelope("{}", 401), HttpStatusCode.Unauthorized, headers = headers)
                    } else {
                        respond(
                            envelope("{\"track_list\":[{\"track\":${track(1)}},{\"track\":${track(2)}},{\"track\":${track(3)}},{\"track\":${track(4)}}]}"),
                            headers = headers,
                        )
                    }
                }
                "token.get" -> respond(envelope("{\"user_token\":\"renewed_test_token\"}"), headers = headers)
                "macro.subtitles.get" -> {
                    val id = request.url.parameters["track_id"]?.toLongOrNull() ?: 1L
                    respond(macro(id), headers = headers)
                }
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "initial_invalid_token"
        Musixmatch.getAllLyrics("Koiiro", "Mosawo", durationSeconds = 100) { results.add(it) }
        assertEquals(4, results.size)
        assertEquals(setOf("Lyrics 1", "Lyrics 2", "Lyrics 3", "Lyrics 4"), results.toSet())
    }

    @Test
    fun renewalFailureCauseDoesNotContainTokenOrUrl() = runBlocking {
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(envelope("{}", 401), HttpStatusCode.Unauthorized, headers = headers)
                "token.get" -> respond("Error 500", HttpStatusCode.InternalServerError, headers = headers)
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "secret_user_token_123"
        val error = try {
            Musixmatch.getAllLyrics("Koiiro", "Mosawo") {}
            null
        } catch (e: MusixmatchError.TokenInvalid) { e }
        assertNotNull(error)
        val fullMessage = "${error!!.message} ${error.cause?.message}"
        assertFalse(fullMessage.contains("secret_user_token_123"))
        assertFalse(fullMessage.contains("https://"))
        assertFalse(fullMessage.contains("usertoken"))
    }

    @Test
    fun playbackFallbackNoEarlyStopWhenRank1IsQuality1AndRank2CanBeQuality2() = runBlocking {
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(
                    envelope("{\"track_list\":[{\"track\":${track(2)}},{\"track\":${track(3, richsync = 1)}}]}"),
                    headers = headers,
                )
                "macro.subtitles.get" -> {
                    val id = request.url.parameters["track_id"]?.toLongOrNull()
                    if (id == null) {
                        respond(macro(matcherStatus = 404), headers = headers)
                    } else if (id == 2L) {
                        respond(envelope("""{"macro_calls":{
                            "matcher.track.get":${call("{\"track\":${track(2)}}")},
                            "track.subtitles.get":${call("{\"subtitle_list\":[{\"subtitle\":{\"subtitle_body\":\"[00:01.00] Line 1\"}}]}")}
                        }}"""), headers = headers)
                    } else {
                        val richsyncJson = """[{"ts":1.0,"te":5.0,"l":[{"c":"Word","o":0.0}],"x":"Word"}]"""
                        respond(envelope("""{"macro_calls":{
                            "matcher.track.get":${call("{\"track\":${track(3, richsync = 1)}}")},
                            "track.richsync.get":${call("{\"richsync\":{\"richsync_body\":${quote(richsyncJson)}}}")}
                        }}"""), headers = headers)
                    }
                }
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
        val res = playback().getOrThrow()
        assertTrue(res.contains("<tt"))
        assertEquals(listOf(2L, 3L), ids())
    }

    @Test
    fun fanOutSingleCandidate_richsyncEnrichmentRateLimited_withInlineSubtitle_yieldsSubtitleAndThrowsRateLimited() = runBlocking {
        val results = mutableListOf<String>()
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(
                    envelope("{\"track_list\":[{\"track\":${track(1, richsync = 1, sub = 1)}}]}"),
                    headers = headers,
                )
                "macro.subtitles.get" -> respond(
                    macro(1L, track = track(1, richsync = 1, sub = 1), subtitle = "[00:01.00] Line 1"),
                    headers = headers,
                )
                "track.richsync.get" -> respond(envelope("{}", 429), HttpStatusCode.TooManyRequests, headers = headers)
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
        val failure = try {
            Musixmatch.getAllLyrics("Koiiro", "Mosawo", durationSeconds = 100) { results.add(it) }
            null
        } catch (e: MusixmatchError) { e }
        assertTrue(failure is MusixmatchError.RateLimited)
        assertEquals(listOf("[00:01.00] Line 1"), results)
        assertTrue(requests.any { it.encodedPath.endsWith("track.richsync.get") })
    }

    @Test
    fun fanOutSingleCandidate_richsyncEnrichmentTokenRenewalFails_withInlineSubtitle_yieldsSubtitleAndThrowsTokenInvalid() = runBlocking {
        val results = mutableListOf<String>()
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(
                    envelope("{\"track_list\":[{\"track\":${track(1, richsync = 1, sub = 1)}}]}"),
                    headers = headers,
                )
                "macro.subtitles.get" -> respond(
                    macro(1L, track = track(1, richsync = 1, sub = 1), subtitle = "[00:01.00] Line 1"),
                    headers = headers,
                )
                "track.richsync.get" -> respond(envelope("{}", 401), HttpStatusCode.Unauthorized, headers = headers)
                "token.get" -> respond("Internal Server Error", HttpStatusCode.InternalServerError, headers = headers)
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
        val failure = try {
            Musixmatch.getAllLyrics("Koiiro", "Mosawo", durationSeconds = 100) { results.add(it) }
            null
        } catch (e: MusixmatchError) { e }
        assertTrue(failure is MusixmatchError.TokenInvalid)
        assertEquals(listOf("[00:01.00] Line 1"), results)
        assertTrue(requests.any { it.encodedPath.endsWith("track.richsync.get") })
        assertTrue(requests.any { it.encodedPath.endsWith("token.get") })
    }

    @Test
    fun playbackMain_richsyncEnrichmentRateLimited_withInlineSubtitle_throwsRateLimited() = runBlocking {
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "macro.subtitles.get" -> respond(
                    macro(1L, track = track(1, richsync = 1, sub = 1), subtitle = "[00:01.00] Line 1"),
                    headers = headers,
                )
                "track.richsync.get" -> respond(envelope("{}", 429), HttpStatusCode.TooManyRequests, headers = headers)
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
        val result = playback()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MusixmatchError.RateLimited)
        assertTrue(requests.any { it.encodedPath.endsWith("track.richsync.get") })
    }

    @Test
    fun titleFallback_richsyncEnrichmentRateLimited_withInlineSubtitle_throwsRateLimited() = runBlocking {
        requests.clear()
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(envelope("{\"track_list\":[]}"), headers = headers)
                "macro.subtitles.get" -> respond(
                    macro(1L, track = track(1, richsync = 1, sub = 1), subtitle = "[00:01.00] Line 1"),
                    headers = headers,
                )
                "track.richsync.get" -> respond(envelope("{}", 429), HttpStatusCode.TooManyRequests, headers = headers)
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "cached_test_token"
        val failure = try {
            Musixmatch.getAllLyrics("Koiiro", "Mosawo") {}
            null
        } catch (e: MusixmatchError) { e }
        assertTrue(failure is MusixmatchError.RateLimited)
        assertTrue(requests.any { it.encodedPath.endsWith("track.richsync.get") })
    }

    private fun quote(value: String): String = Json.encodeToString(value)
    private fun track(id: Long, length: Int = 100, instrumental: Int = 0, title: String = "Koiiro", artist: String = "Mosawo", richsync: Int = 0, sub: Int = 0): String =
        """{"track_id":$id,"commontrack_id":${id + 1000},"track_name":${quote(title)},"artist_name":${quote(artist)},"track_length":$length,"instrumental":$instrumental,"has_richsync":$richsync,"has_subtitles":$sub}"""
    private fun envelope(body: String, status: Int = 200) = """{"message":{"header":{"status_code":$status},"body":$body}}"""
    private fun call(body: String, status: Int = 200) = """{"message":{"header":{"status_code":$status},"body":$body}}"""
    private fun macro(
        id: Long = 1,
        track: String = track(id),
        matcherStatus: Int = 200,
        restricted: Boolean = false,
        subtitle: String? = null,
    ): String {
        val subtitleCall = if (subtitle != null) {
            ""","track.subtitles.get":${call("{\"subtitle_list\":[{\"subtitle\":{\"subtitle_body\":${quote(subtitle)}}}]}")}"""
        } else ""
        return envelope(
            """{"macro_calls":{"matcher.track.get":${call("{\"track\":$track}", matcherStatus)},"track.lyrics.get":${call("{\"lyrics\":{\"lyrics_body\":\"Lyrics $id\",\"restricted\":${if (restricted) 1 else 0}}}")}$subtitleCall}}"""
        )
    }
}
