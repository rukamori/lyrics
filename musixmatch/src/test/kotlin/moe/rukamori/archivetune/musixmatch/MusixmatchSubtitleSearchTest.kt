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
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class MusixmatchSubtitleSearchTest {
    private val requests = Collections.synchronizedList(mutableListOf<Url>())
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    @After
    fun tearDown() {
        Musixmatch.resetForTesting()
        Musixmatch.logger = null
    }

    private fun setup(
        subtitleAvailable: Boolean = true,
        secondCandidate: Boolean = false,
        subtitleStatuses: List<Int> = listOf(200),
        inlineSubtitle: String? = null,
        plain: String? = null,
        separateBody: String = subtitleBody("Separate subtitle winner"),
    ) {
        requests.clear()
        logs.clear()
        Musixmatch.logger = { logs.add(it) }
        var subtitleCalls = 0
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            val body = when (request.url.encodedPath.substringAfterLast('/')) {
                "token.get" -> envelope("{\"user_token\":\"renewed_test_token\"}")
                "track.search" -> envelope("{\"track_list\":[{\"track\":${track(1, subtitleAvailable)}}${if (secondCandidate) ",{\"track\":${track(2, false)}}" else ""}]}")
                "macro.subtitles.get" -> {
                    val id = request.url.parameters["track_id"]?.toIntOrNull() ?: 1
                    if (id == 2) macro(2, false, plain = "Second candidate")
                    else macro(1, subtitleAvailable, inlineSubtitle, plain)
                }
                "track.subtitle.get" -> {
                    val status = subtitleStatuses.getOrElse(subtitleCalls++) { subtitleStatuses.last() }
                    envelope(separateBody, status)
                }
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
            respond(body, headers = headers)
        })
        Musixmatch.cachedToken = "cached_test_token"
    }

    private suspend fun search(): List<String> {
        val results = mutableListOf<String>()
        Musixmatch.getAllLyrics("Song", "Artist", durationSeconds = 100, callback = results::add)
        return results
    }

    private fun subtitleRequests() = requests.filter { it.encodedPath.endsWith("track.subtitle.get") }

    @Test
    fun missingInlineSubtitleFetchesByTrackIdAndEmitsLrc() = runBlocking {
        setup()
        val result = search().single()
        assertTrue(result.contains("[00:02.500] Separate subtitle winner"))
        val req = subtitleRequests().single()
        assertEquals("1", req.parameters["track_id"])
        assertEquals("mxm", req.parameters["subtitle_format"])
        assertEquals("100", req.parameters["f_subtitle_length"])
        assertTrue(logs.any { it.endsWith("separate subtitle request started") })
    }

    @Test
    fun unavailableSubtitleMakesNoSeparateRequest() = runBlocking {
        setup(subtitleAvailable = false)
        assertEquals(emptyList<String>(), search())
        assertTrue(subtitleRequests().isEmpty())
    }

    @Test
    fun ordinaryStandaloneFailureKeepsOtherCandidates() = runBlocking {
        setup(secondCandidate = true, subtitleStatuses = listOf(500))
        assertEquals(listOf("Second candidate"), search())
        assertEquals(1, subtitleRequests().size)
    }

    @Test
    fun standaloneSubtitleWinsOverPlainInBothPaths() = runBlocking {
        setup(plain = "Plain loser")
        val playback = Musixmatch.getLyrics("Song", "Artist", durationSeconds = 100).getOrThrow()
        val searched = search().single()
        assertEquals(playback, searched)
        assertTrue(playback.contains("Separate subtitle winner"))
        assertFalse(playback.contains("Plain loser"))
    }

    @Test
    fun inlineSubtitleAvoidsExtraRequest() = runBlocking {
        setup(inlineSubtitle = subtitleLines("Inline winner"))
        val searched = search().single()
        assertTrue(searched.contains("Inline winner"))
        assertTrue(subtitleRequests().isEmpty())
    }

    @Test
    fun standaloneFailureOrMalformedSubtitleRetainsPlainFallback() = runBlocking {
        setup(subtitleStatuses = listOf(500), plain = "Plain winner")
        assertEquals(listOf("Plain winner"), search())
        setup(separateBody = "{\"subtitle\":{\"subtitle_body\":\"not json\"}}", plain = "Plain winner")
        assertEquals(listOf("Plain winner"), search())
    }

    @Test
    fun standaloneRateLimitIsTerminal() = runBlocking {
        setup(subtitleStatuses = listOf(429))
        val failure = try { search(); null } catch (e: MusixmatchError) { e }
        assertTrue(failure is MusixmatchError.RateLimited)
        assertEquals(1, subtitleRequests().size)
        assertFalse(requests.any { it.encodedPath.endsWith("token.get") })
    }

    @Test
    fun standaloneUnauthorizedRenewsOnceAndRetriesSameEndpoint() = runBlocking {
        setup(subtitleStatuses = listOf(401, 200))
        assertTrue(search().single().contains("Separate subtitle winner"))
        assertEquals(2, subtitleRequests().size)
        assertEquals(1, requests.count { it.encodedPath.endsWith("token.get") })
        assertEquals(listOf("cached_test_token", "renewed_test_token"), subtitleRequests().map { it.parameters["usertoken"] })
    }

    @Test
    fun rejectedRenewedStandaloneTokenStopsOperation() = runBlocking {
        setup(subtitleStatuses = listOf(401))
        val failure = try { search(); null } catch (e: MusixmatchError) { e }
        assertTrue(failure is MusixmatchError.TokenInvalid)
        assertEquals(2, subtitleRequests().size)
        assertEquals(1, requests.count { it.encodedPath.endsWith("token.get") })
    }

    @Test
    fun inline200WithEmptyBodyFallsBackToStandaloneSubtitle() = runBlocking {
        for (emptyBody in listOf("[]", "", "   ", "invalid lrc json")) {
            setup(subtitleAvailable = true, inlineSubtitle = emptyBody)
            val lyrics = search()
            assertEquals(1, lyrics.size)
            assertTrue(lyrics.first().contains("Separate subtitle winner"))
            assertEquals(1, subtitleRequests().size)
        }
    }

    private fun quote(value: String) = Json.encodeToString(value)
    private fun track(id: Int, sub: Boolean): String =
        """{"track_id":$id,"commontrack_id":${id + 1000},"track_name":"Song","artist_name":"Artist","track_length":100,"has_richsync":0,"has_subtitles":${if (sub) 1 else 0}}"""
    private fun envelope(body: String, status: Int = 200) = """{"message":{"header":{"status_code":$status},"body":$body}}"""
    private fun subtitleLines(text: String): String = """[{"text":${quote(text)},"time":{"total":2.5}}]"""
    private fun subtitleBody(text: String): String =
        """{"subtitle":{"subtitle_body":${quote(subtitleLines(text))}}}"""

    private fun macro(id: Int, sub: Boolean, inlineSubtitle: String? = null, plain: String? = null): String {
        val subtitleEnvelope = inlineSubtitle?.let {
            """{"subtitle_list":[{"subtitle":{"subtitle_body":${quote(it)}}}]}"""
        }
        return envelope("""{"macro_calls":{
            "matcher.track.get":${envelope("{\"track\":${track(id, sub)}}")},
            "track.richsync.get":${envelope("{}", 404)},
            "track.subtitles.get":${envelope(subtitleEnvelope ?: "{}", if (inlineSubtitle == null) 404 else 200)},
            "track.lyrics.get":${envelope(plain?.let { "{\"lyrics\":{\"lyrics_body\":${quote(it)}}}" } ?: "{}", if (plain == null) 404 else 200)}
        }}""")
    }
}
