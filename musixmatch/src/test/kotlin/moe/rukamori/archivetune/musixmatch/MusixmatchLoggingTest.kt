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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

class MusixmatchLoggingTest {

    private val loggedMessages = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        loggedMessages.clear()
        Musixmatch.logger = { line -> loggedMessages.add(line) }
    }

    @After
    fun tearDown() {
        Musixmatch.logger = null
        Musixmatch.resetForTesting(null)
    }

    private fun jsonHeader() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun tokenResponse(token: String = "mock-secret-token-12345") = """
        {
          "message": {
            "header": {"status_code": 200},
            "body": {
              "user_token": "$token"
            }
          }
        }
    """.trimIndent()

    private fun macroResponse(trackId: Long, name: String, lyrics: String, instrumental: Int = 0) = """
        {
          "message": {
            "header": {"status_code": 200},
            "body": {
              "macro_calls": {
                "matcher.track.get": {
                  "message": {
                    "header": {"status_code": 200},
                    "body": {
                      "track": {
                        "track_id": $trackId,
                        "track_name": "$name",
                        "artist_name": "SecretArtist",
                        "track_length": 200,
                        "instrumental": $instrumental,
                        "has_lyrics": 1
                      }
                    }
                  }
                },
                "track.lyrics.get": {
                  "message": {
                    "header": {"status_code": 200},
                    "body": {
                      "lyrics": {
                        "lyrics_id": ${trackId + 10},
                        "lyrics_body": "$lyrics"
                      }
                    }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    private val validLogPatterns = listOf(
        Regex("""^Musixmatch token request started$"""),
        Regex("""^Musixmatch token acquired successfully$"""),
        Regex("""^Musixmatch (fallback|fan-out): track search request started$"""),
        Regex("""^Musixmatch (playback|title-fallback): macro request started \(by-name\)$"""),
        Regex("""^Musixmatch (fallback|fan-out) ranked \d+: macro by-id request started$"""),
        Regex("""^Musixmatch (playback|(fallback|fan-out) ranked \d+): separate richsync request started$"""),
        Regex("""^Musixmatch (playback|(fallback|fan-out) ranked \d+): separate subtitle request started$"""),
        Regex("""^Musixmatch (playback|title-fallback): (success quality=[0-2]|instrumental|failed \((TokenInvalid|RateLimited|NoMatch|Mismatch|Restricted|Instrumental|ServerError|MalformedResponse|OtherException)\))$"""),
        Regex("""^Musixmatch (fallback|fan-out) ranked \d+: (success demerit=[01] quality=[0-2] rating=\d+|instrumental|failed \((TokenInvalid|RateLimited|NoMatch|Mismatch|Restricted|Instrumental|ServerError|MalformedResponse|OtherException)\)|cancelled \(early-stop\))$"""),
        Regex("""^Musixmatch fallback: ranked search$"""),
        Regex("""^Musixmatch title-fallback: title matcher$"""),
        Regex("""^Musixmatch fallback: early-stop$"""),
        Regex("""^Musixmatch fallback: winner from ranked \d+$"""),
        Regex("""^Musixmatch fallback: all candidates failed \((TokenInvalid|RateLimited|NoMatch|Mismatch|Restricted|Instrumental|ServerError|MalformedResponse|OtherException)\) from ranked \d+$"""),
        Regex("""^Musixmatch fallback: final terminal \((TokenInvalid|RateLimited|ServerError|MalformedResponse)\) from ranked \d+$"""),
        Regex("""^Musixmatch fan-out: queue stopped — terminal \((TokenInvalid|RateLimited)\) at ranked \d+$"""),
        Regex("""^Musixmatch fan-out: final terminal \((TokenInvalid|RateLimited)\) from ranked \d+$"""),
        Regex("""^Musixmatch (fallback|fan-out): search funnel: returned=\d+ eligible=\d+ candidates=\d+$"""),
    )

    private fun assertAllowlistAndNoLeaks(
        sensitiveTokens: List<String> = listOf("mock-secret-token", "SecretTitle", "SecretArtist", "SecretLyricsBody"),
    ) {
        for (log in loggedMessages) {
            val matched = validLogPatterns.any { it.matches(log) }
            assertTrue("Log line does not match Option N allowlist: '$log'", matched)

            for (token in sensitiveTokens) {
                assertFalse("Log line leaked sensitive data '$token': '$log'", log.contains(token))
            }
        }
    }

    @Test
    fun `playback primary success logs follow Option N and leak no sensitive info`() = runBlocking {
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("token.get") -> respond(tokenResponse(), HttpStatusCode.OK, jsonHeader())
                path.contains("macro.subtitles.get") -> respond(macroResponse(10, "SecretTitle", "SecretLyricsBody"), HttpStatusCode.OK, jsonHeader())
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeader())
            }
        }

        Musixmatch.resetForTesting(mockEngine)
        val result = Musixmatch.getLyrics(title = "SecretTitle", artist = "SecretArtist", durationSeconds = 200)
        assertTrue(result.isSuccess)

        assertAllowlistAndNoLeaks()

        // Invariant B: exactly one B line for playback resolver
        val bLines = loggedMessages.filter { it.startsWith("Musixmatch playback:") && (it.contains("success") || it.contains("failed") || it.contains("instrumental")) }
        assertEquals(1, bLines.size)
        assertEquals("Musixmatch playback: success quality=0", bLines.first())
    }

    @Test
    fun `playback fallback logs funnel, ranked candidates, and winner without leaks`() = runBlocking {
        val primaryFail = """
            {"message":{"header":{"status_code":200},"body":{"macro_calls":{"matcher.track.get":{"message":{"header":{"status_code":404}}}}}}}
        """.trimIndent()

        val searchResponse = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "track_list": [
                    {
                      "track": {
                        "track_id": 101,
                        "track_name": "SecretTitle",
                        "artist_name": "SecretArtist",
                        "track_length": 200,
                        "track_rating": 85,
                        "has_lyrics": 1
                      }
                    },
                    {
                      "track": {
                        "track_id": 102,
                        "track_name": "SecretTitle (Acoustic)",
                        "artist_name": "SecretArtist",
                        "track_length": 200,
                        "track_rating": 90,
                        "has_lyrics": 1
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("token.get") -> respond(tokenResponse(), HttpStatusCode.OK, jsonHeader())
                path.contains("track.search") -> respond(searchResponse, HttpStatusCode.OK, jsonHeader())
                path.contains("macro.subtitles.get") -> {
                    val trackId = request.url.parameters["track_id"]?.toLongOrNull()
                    when (trackId) {
                        101L -> respond(macroResponse(101, "SecretTitle", "SecretLyricsBody 101"), HttpStatusCode.OK, jsonHeader())
                        102L -> respond(macroResponse(102, "SecretTitle (Acoustic)", "SecretLyricsBody 102"), HttpStatusCode.OK, jsonHeader())
                        else -> respond(primaryFail, HttpStatusCode.OK, jsonHeader())
                    }
                }
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeader())
            }
        }

        Musixmatch.resetForTesting(mockEngine)
        val result = Musixmatch.getLyrics(title = "SecretTitle", artist = "SecretArtist", durationSeconds = 200)
        assertTrue(result.isSuccess)

        assertAllowlistAndNoLeaks()

        // Check C transition
        assertTrue(loggedMessages.contains("Musixmatch fallback: ranked search"))
        // Check D funnel
        assertTrue(loggedMessages.contains("Musixmatch fallback: search funnel: returned=2 eligible=2 candidates=2"))
        // Check A candidate starts
        assertTrue(loggedMessages.contains("Musixmatch fallback ranked 1: macro by-id request started"))
        assertTrue(loggedMessages.contains("Musixmatch fallback ranked 2: macro by-id request started"))
        // Check B candidate outcomes
        assertTrue(loggedMessages.contains("Musixmatch fallback ranked 1: success demerit=0 quality=0 rating=85"))
        assertTrue(loggedMessages.contains("Musixmatch fallback ranked 2: success demerit=1 quality=0 rating=90"))
        // Check C winner (ranked 1 wins because demerit 0 beats demerit 1)
        assertTrue(loggedMessages.contains("Musixmatch fallback: winner from ranked 1"))
    }

    @Test
    fun `fan-out empty filter fallback logs title-fallback path`() = runBlocking {
        val emptySearch = """
            {"message":{"header":{"status_code":200},"body":{"track_list":[]}}}
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("token.get") -> respond(tokenResponse(), HttpStatusCode.OK, jsonHeader())
                path.contains("track.search") -> respond(emptySearch, HttpStatusCode.OK, jsonHeader())
                path.contains("macro.subtitles.get") -> respond(macroResponse(999, "SecretTitle", "SecretLyricsBody"), HttpStatusCode.OK, jsonHeader())
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeader())
            }
        }

        Musixmatch.resetForTesting(mockEngine)
        val emitted = mutableListOf<String>()
        Musixmatch.getAllLyrics(title = "SecretTitle", artist = "SecretArtist", durationSeconds = 200) { emitted.add(it) }

        assertEquals(1, emitted.size)
        assertAllowlistAndNoLeaks()

        assertTrue(loggedMessages.contains("Musixmatch fan-out: track search request started"))
        assertTrue(loggedMessages.contains("Musixmatch fan-out: search funnel: returned=0 eligible=0 candidates=0"))
        assertTrue(loggedMessages.contains("Musixmatch title-fallback: title matcher"))
        assertTrue(loggedMessages.contains("Musixmatch title-fallback: macro request started (by-name)"))
        assertTrue(loggedMessages.contains("Musixmatch title-fallback: success quality=0"))
    }

    @Test
    fun `fan-out with terminal error logs queue stop and final terminal`() = runBlocking {
        val searchResponse = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "track_list": [
                    {"track": {"track_id": 201, "track_name": "T1", "artist_name": "A", "track_length": 200, "has_lyrics": 1}},
                    {"track": {"track_id": 202, "track_name": "T2", "artist_name": "A", "track_length": 200, "has_lyrics": 1}},
                    {"track": {"track_id": 203, "track_name": "T3", "artist_name": "A", "track_length": 200, "has_lyrics": 1}}
                  ]
                }
              }
            }
        """.trimIndent()

        val rateLimitedResponse = """
            {"message":{"header":{"status_code":429}}}
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("token.get") -> respond(tokenResponse(), HttpStatusCode.OK, jsonHeader())
                path.contains("track.search") -> respond(searchResponse, HttpStatusCode.OK, jsonHeader())
                path.contains("macro.subtitles.get") -> respond(rateLimitedResponse, HttpStatusCode.TooManyRequests, jsonHeader())
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeader())
            }
        }

        Musixmatch.resetForTesting(mockEngine)
        var thrown: Throwable? = null
        try {
            Musixmatch.getAllLyrics(title = "SecretTitle", artist = "SecretArtist", durationSeconds = 200) {}
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue("Expected MusixmatchError.RateLimited", thrown is MusixmatchError.RateLimited)
        assertAllowlistAndNoLeaks()

        val finalTerminalLine = loggedMessages.firstOrNull { it.contains("final terminal") }
        assertTrue("Expected final terminal log", finalTerminalLine != null)
        assertTrue(finalTerminalLine!!.contains("RateLimited"))
    }
}
