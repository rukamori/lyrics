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
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import moe.rukamori.archivetune.musixmatch.models.MacroCall
import moe.rukamori.archivetune.musixmatch.models.MacroMessage
import moe.rukamori.archivetune.musixmatch.models.MatcherBody
import moe.rukamori.archivetune.musixmatch.models.MusixmatchHeader
import moe.rukamori.archivetune.musixmatch.models.SubtitlesBody
import moe.rukamori.archivetune.musixmatch.models.Track
import moe.rukamori.archivetune.musixmatch.models.bodyAs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MusixmatchApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val jsonFormat =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    @After
    fun tearDown() {
        Musixmatch.resetForTesting(null)
        Musixmatch.logger = null
    }

    private fun jsonTokenResponse(
        statusCode: Int = 200,
        token: String = "test_user_token_abc12345",
    ): String =
        """
        {
            "message": {
                "header": { "status_code": $statusCode },
                "body": { "user_token": "$token" }
            }
        }
        """.trimIndent()

    private fun jsonMacroResponse(
        statusCode: Int = 200,
        matcherStatus: Int = 200,
        trackName: String = "Levitating",
        artistName: String = "Dua Lipa",
        trackLength: Int = 204,
        hasRichsync: Int = 1,
        hasSubtitles: Int = 1,
        hasLyrics: Int = 1,
        instrumental: Int = 0,
        restricted: Int = 0,
        richsyncBodyJson: String? = null,
        richsyncStatus: Int = 200,
        subtitleBodyJson: String? = null,
        lyricsBodyText: String? = "I got you, moonlight, you're my starlight",
    ): String {
        val escapedRichsync = richsyncBodyJson?.let { Json.encodeToString(it) } ?: "\"\""
        val escapedSubtitles = subtitleBodyJson?.let { Json.encodeToString(it) } ?: "\"\""
        val escapedLyrics = lyricsBodyText?.let { Json.encodeToString(it) } ?: "\"\""

        return """
        {
            "message": {
                "header": { "status_code": $statusCode },
                "body": {
                    "macro_calls": {
                        "matcher.track.get": {
                            "message": {
                                "header": { "status_code": $matcherStatus },
                                "body": {
                                    "track": {
                                        "track_id": 12345,
                                        "commontrack_id": 67890,
                                        "track_name": "$trackName",
                                        "artist_name": "$artistName",
                                        "track_length": $trackLength,
                                        "has_richsync": $hasRichsync,
                                        "has_subtitles": $hasSubtitles,
                                        "has_lyrics": $hasLyrics,
                                        "instrumental": $instrumental
                                    }
                                }
                            }
                        },
                        "track.lyrics.get": {
                            "message": {
                                "header": { "status_code": 200 },
                                "body": {
                                    "lyrics": {
                                        "lyrics_id": 9999,
                                        "lyrics_body": $escapedLyrics,
                                        "restricted": $restricted,
                                        "instrumental": $instrumental
                                    }
                                }
                            }
                        },
                        "track.subtitles.get": {
                            "message": {
                                "header": { "status_code": 200 },
                                "body": {
                                    "subtitle_list": [
                                        {
                                            "subtitle": {
                                                "subtitle_body": $escapedSubtitles
                                            }
                                        }
                                    ]
                                }
                            }
                        },
                        "track.richsync.get": {
                            "message": {
                                "header": { "status_code": $richsyncStatus },
                                "body": {
                                    "richsync": {
                                        "richsync_id": 8888,
                                        "richsync_body": $escapedRichsync
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        """.trimIndent()
    }

    @Test
    fun testTokenGet_success_storesToken() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond("Not Found", HttpStatusCode.NotFound)
                    }
                }
            Musixmatch.resetForTesting(engine)

            val token = Musixmatch.getToken()
            assertEquals("test_user_token_abc12345", token)
            assertEquals("test_user_token_abc12345", Musixmatch.cachedToken)
        }

    @Test
    fun testTokenGet_parallel_onlyOneNetworkCall() =
        runBlocking {
            val requestCount = AtomicInteger(0)
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        delay(50)
                        requestCount.incrementAndGet()
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond("Not Found", HttpStatusCode.NotFound)
                    }
                }
            Musixmatch.resetForTesting(engine)

            val (res1, res2) =
                withContext(Dispatchers.Default) {
                    val call1 = async { Musixmatch.getToken() }
                    val call2 = async { Musixmatch.getToken() }
                    call1.await() to call2.await()
                }

            assertEquals("test_user_token_abc12345", res1)
            assertEquals("test_user_token_abc12345", res2)
            assertEquals(1, requestCount.get())
        }

    @Test
    fun testTokenGet_status200_bodyStatus401_detectedAsRateLimited() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(jsonTokenResponse(statusCode = 401), HttpStatusCode.OK, jsonHeaders)
                }
            Musixmatch.resetForTesting(engine)

            try {
                Musixmatch.getToken()
                assertTrue("Expected MusixmatchError.RateLimited", false)
            } catch (e: MusixmatchError.RateLimited) {
                assertTrue(e.message?.contains("401") == true)
            }
        }

    @Test
    fun testTokenGet_401_rateLimited_noRetry() =
        runBlocking {
            val count = AtomicInteger(0)
            val engine =
                MockEngine {
                    count.incrementAndGet()
                    respond("Unauthorized", HttpStatusCode.Unauthorized, jsonHeaders)
                }
            Musixmatch.resetForTesting(engine)

            try {
                Musixmatch.getToken()
                assertTrue("Expected MusixmatchError.RateLimited", false)
            } catch (e: MusixmatchError.RateLimited) {
                assertEquals(1, count.get())
            }
        }

    @Test
    fun testMacro_429_doesNotCallTokenGet() =
        runBlocking {
            var tokenCalls = 0
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        tokenCalls++
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond("Too Many Requests", HttpStatusCode.TooManyRequests, jsonHeaders)
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is MusixmatchError.RateLimited)
            assertEquals(1, tokenCalls)
        }

    @Test
    fun testMacro_5xx_doesNotCallTokenGetAgain() =
        runBlocking {
            var tokenCalls = 0
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        tokenCalls++
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond("Internal Server Error", HttpStatusCode.InternalServerError, jsonHeaders)
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is MusixmatchError.ServerError)
            assertEquals(1, tokenCalls)
        }

    @Test
    fun testMacro_401_renewsTokenOnceAndRetriesOnce() =
        runBlocking {
            var macroCalls = 0
            var tokenCalls = 0
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        tokenCalls++
                        respond(jsonTokenResponse(token = "token_$tokenCalls"), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        macroCalls++
                        if (macroCalls == 1) {
                            respond("Unauthorized", HttpStatusCode.Unauthorized, jsonHeaders)
                        } else {
                            respond(
                                jsonMacroResponse(lyricsBodyText = "Recovered plain lyrics", hasRichsync = 0, hasSubtitles = 0),
                                HttpStatusCode.OK,
                                jsonHeaders,
                            )
                        }
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa")
            assertTrue(result.isSuccess)
            assertEquals("Recovered plain lyrics", result.getOrNull())
            assertEquals(2, tokenCalls)
            assertEquals(2, macroCalls)
        }

    @Test
    fun testMacro_malformedJson_failsWithoutRenew() =
        runBlocking {
            var tokenCalls = 0
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        tokenCalls++
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond("<html>Malformed</html>", HttpStatusCode.OK, jsonHeaders)
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is MusixmatchError.MalformedResponse)
            assertEquals(1, tokenCalls)
        }

    @Test
    fun testMacro_bodyAs_handlesEmptyArrayBodySafely() {
        val call =
            MacroCall(
                message =
                    MacroMessage(
                        header = MusixmatchHeader(statusCode = 200),
                        body = JsonArray(emptyList()),
                    ),
            )

        val result = call.bodyAs<SubtitlesBody>(jsonFormat)
        assertNull("Empty array body must decode as null without throwing", result)
    }

    @Test
    fun testMacro_bodyAs_nullAndAbsentBody_returnsNullWithoutException() {
        val callNull =
            MacroCall(
                message =
                    MacroMessage(
                        header = MusixmatchHeader(statusCode = 200),
                        body = null,
                    ),
            )
        assertNull(callNull.bodyAs<SubtitlesBody>(jsonFormat))

        val callMissing: MacroCall? = null
        assertNull(callMissing.bodyAs<SubtitlesBody>(jsonFormat))
    }

    @Test
    fun testTrackFlags_integer0And1_decodedCorrectly() {
        val trackJson =
            """
            {
                "track": {
                    "track_id": 101,
                    "commontrack_id": 202,
                    "track_name": "Test Song",
                    "artist_name": "Test Artist",
                    "track_length": 180,
                    "has_richsync": 1,
                    "has_subtitles": 0,
                    "has_lyrics": 1,
                    "has_lyrics_crowd": 0,
                    "instrumental": 0
                }
            }
            """.trimIndent()

        val call =
            MacroCall(
                message =
                    MacroMessage(
                        header = MusixmatchHeader(statusCode = 200),
                        body = jsonFormat.parseToJsonElement(trackJson),
                    ),
            )

        val matcherBody = call.bodyAs<MatcherBody>(jsonFormat)
        assertNotNull(matcherBody)
        val track = matcherBody?.track
        assertNotNull(track)
        assertEquals(1, track?.hasRichsync)
        assertTrue(track?.isRichsyncAvailable == true)
        assertEquals(0, track?.hasSubtitles)
        assertFalse(track?.isSubtitlesAvailable == true)
        assertTrue(track?.isLyricsAvailable == true)
        assertFalse(track?.isInstrumental == true)
    }

    @Test
    fun testMatcherGuard_durationMismatchOver8Seconds_rejected() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else if (request.url.encodedPath.endsWith("track.search")) {
                        respond("""{"message":{"header":{"status_code":200},"body":{"track_list":[]}}}""", HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond(jsonMacroResponse(trackLength = 204), HttpStatusCode.OK, jsonHeaders)
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa", durationSeconds = 100)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is MusixmatchError.NoMatch)
        }

    @Test
    fun testMatcherGuard_durationDiffWithin8Seconds_passes() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond(
                            jsonMacroResponse(trackLength = 204, lyricsBodyText = "Valid duration lyrics"),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa", durationSeconds = 200)
            assertTrue(result.isSuccess)
            assertEquals("Valid duration lyrics", result.getOrNull())
        }

    @Test
    fun testMatcherGuard_durationNegative1_skipsDurationGuard() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond(
                            jsonMacroResponse(trackLength = 500, lyricsBodyText = "Skipped duration check lyrics"),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa", durationSeconds = -1)
            assertTrue(result.isSuccess)
        }

    @Test
    fun testMatcherGuard_textMismatchBoth_fails() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else if (request.url.encodedPath.endsWith("track.search")) {
                        respond("""{"message":{"header":{"status_code":200},"body":{"track_list":[]}}}""", HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond(
                            jsonMacroResponse(trackName = "Different", artistName = "Unrelated"),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is MusixmatchError.NoMatch)
        }

    @Test
    fun testMatcherGuard_textPunctuationOnly_failsMismatch() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else if (request.url.encodedPath.endsWith("track.search")) {
                        respond("""{"message":{"header":{"status_code":200},"body":{"track_list":[]}}}""", HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond(
                            jsonMacroResponse(trackName = "Song", artistName = "Artist"),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("!!!", "???")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is MusixmatchError.NoMatch)
        }

    @Test
    fun testInstrumental_returnsMarker_doesNotCallRichsync() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond(
                            jsonMacroResponse(
                                trackName = "YYZ",
                                artistName = "Rush",
                                instrumental = 1,
                                richsyncBodyJson = """[{"ts":1.0,"te":3.0,"l":[{"c":"Never","o":0.0}],"x":"Never"}]""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("YYZ", "Rush")
            assertTrue(result.isSuccess)
            assertEquals("♪ Instrumental ♪", result.getOrNull())
        }

    @Test
    fun testRestricted_returnsFailureEarly() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond(
                            jsonMacroResponse(
                                restricted = 1,
                                richsyncBodyJson = """[{"ts":1.0,"te":3.0,"l":[{"c":"Restricted","o":0.0}],"x":"Restricted"}]""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is MusixmatchError.Restricted)
        }

    @Test
    fun playbackEarlyExitsDoNotRequestMissingRichsync() = runBlocking {
        for (restricted in listOf(false, true)) {
            val separateRequests = AtomicInteger()
            Musixmatch.resetForTesting(MockEngine { request ->
                if (request.url.encodedPath.endsWith("macro.subtitles.get")) {
                    respond(
                        jsonMacroResponse(
                            instrumental = if (restricted) 0 else 1,
                            restricted = if (restricted) 1 else 0,
                        ).replace("\"richsync_body\": \"\"", "\"richsync_body\": null"),
                        headers = jsonHeaders,
                    )
                } else {
                    separateRequests.incrementAndGet()
                    respond("{}", headers = jsonHeaders)
                }
            })
            Musixmatch.cachedToken = "cached_playback_token"
            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa")
            if (restricted) {
                assertTrue(result.exceptionOrNull() is MusixmatchError.Restricted)
            } else {
                assertEquals("♪ Instrumental ♪", result.getOrNull())
            }
            assertEquals(0, separateRequests.get())
        }
    }

    @Test
    fun playbackRejectsRichsyncFromFailedSubcall() = runBlocking {
        Musixmatch.resetForTesting(MockEngine {
            respond(
                jsonMacroResponse(
                    hasRichsync = 0,
                    richsyncStatus = 404,
                    richsyncBodyJson = """[{"ts":1.0,"te":3.0,"l":[{"c":"Wrong format","o":0.0}],"x":"Wrong format"}]""",
                    subtitleBodyJson = """[{"text":"Valid fallback","time":{"total":2.0}}]""",
                ),
                headers = jsonHeaders,
            )
        })
        Musixmatch.cachedToken = "cached_playback_token"
        assertEquals("[00:02.000] Valid fallback", Musixmatch.getLyrics("Levitating", "Dua Lipa").getOrThrow())
    }

    @Test
    fun testFallback_richsyncFails_fallsBackToLrc() =
        runBlocking {
            val subtitles =
                """
                [
                    {"text": "Line one", "time": {"total": 5.5}},
                    {"text": "Line two", "time": {"total": 10.25}}
                ]
                """.trimIndent()

            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond(
                            jsonMacroResponse(
                                richsyncBodyJson = "not a json",
                                subtitleBodyJson = subtitles,
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa")
            assertTrue(result.isSuccess)
            val lyrics = result.getOrNull()
            assertTrue("Should contain LRC timestamps", lyrics?.contains("[00:05.500] Line one") == true)
            assertTrue("Should contain second line timestamp", lyrics?.contains("[00:10.250] Line two") == true)
        }

    @Test
    fun testFallback_lrcFails_fallsBackToPlain() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond(
                            jsonMacroResponse(
                                richsyncBodyJson = "",
                                subtitleBodyJson = "",
                                lyricsBodyText = "Simple plain lyrics\nSecond line",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa")
            assertTrue(result.isSuccess)
            assertEquals("Simple plain lyrics\nSecond line", result.getOrNull())
        }

    @Test
    fun testFallback_plainEmpty_failsNoMatch() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        respond(jsonTokenResponse(), HttpStatusCode.OK, jsonHeaders)
                    } else if (request.url.encodedPath.endsWith("track.search")) {
                        respond("""{"message":{"header":{"status_code":200},"body":{"track_list":[]}}}""", HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond(
                            jsonMacroResponse(
                                richsyncBodyJson = "",
                                subtitleBodyJson = "",
                                lyricsBodyText = "",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is MusixmatchError.NoMatch)
        }

    @Test
    fun testLogger_neverReceivesTokenUrlBodyLyrics() =
        runBlocking {
            val sensitiveToken = "super_secret_token_12345"
            val sensitiveLyrics = "This is super secret copyrighted lyrics line"
            val loggedMessages = mutableListOf<String>()

            Musixmatch.logger = { msg -> loggedMessages.add(msg) }

            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("token.get")) {
                        respond(jsonTokenResponse(token = sensitiveToken), HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond(
                            jsonMacroResponse(lyricsBodyText = sensitiveLyrics),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                }
            Musixmatch.resetForTesting(engine)

            val result = Musixmatch.getLyrics("Levitating", "Dua Lipa")
            assertTrue(result.isSuccess)

            assertTrue("Logger must receive informational logs", loggedMessages.isNotEmpty())
            for (msg in loggedMessages) {
                assertFalse("Logger leaked token: $msg", msg.contains(sensitiveToken))
                assertFalse("Logger leaked lyrics: $msg", msg.contains(sensitiveLyrics))
                assertFalse("Logger leaked usertoken parameter: $msg", msg.contains("usertoken="))
                assertFalse("Logger leaked json body: $msg", msg.contains("macro_calls") || msg.contains("\"header\""))
            }
        }

    @Test
    fun testClient_threadSafeLazyInitialization() =
        runBlocking {
            Musixmatch.resetForTesting(null)
            val instances =
                withContext(Dispatchers.Default) {
                    (1..32)
                        .map {
                            async { Musixmatch.client }
                        }.map { it.await() }
                }

            val first = instances.first()
            instances.forEach { instance ->
                assertTrue("All concurrent accesses must yield the exact same HttpClient instance", instance === first)
            }
        }
}
