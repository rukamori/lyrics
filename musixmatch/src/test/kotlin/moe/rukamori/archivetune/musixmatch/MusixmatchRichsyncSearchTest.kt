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
import io.ktor.http.headersOf
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class MusixmatchRichsyncSearchTest {
    private val requests = Collections.synchronizedList(mutableListOf<Url>())
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    @After
    fun tearDown() {
        Musixmatch.resetForTesting()
        Musixmatch.logger = null
    }

    private fun setup(
        richAvailable: Boolean = true,
        secondCandidate: Boolean = false,
        richStatuses: List<Int> = listOf(200),
        inline: String? = null,
        subtitle: String? = null,
        plain: String? = null,
        separateBody: String = richBody("Separate winner"),
    ) {
        requests.clear()
        var richCalls = 0
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            val body = when (request.url.encodedPath.substringAfterLast('/')) {
                "token.get" -> envelope("{\"user_token\":\"renewed_test_token\"}")
                "track.search" -> envelope("{\"track_list\":[{\"track\":${track(1, richAvailable)}}${if (secondCandidate) ",{\"track\":${track(2, false)}}" else ""}]}")
                "macro.subtitles.get" -> {
                    val id = request.url.parameters["track_id"]?.toIntOrNull() ?: 1
                    if (id == 2) macro(2, false, plain = "Second candidate")
                    else macro(1, richAvailable, inline, subtitle, plain)
                }
                "track.richsync.get" -> {
                    val status = richStatuses.getOrElse(richCalls++) { richStatuses.last() }
                    envelope(separateBody, status)
                }
                else -> error("Unexpected endpoint")
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

    private fun richRequests() = requests.filter { it.encodedPath.endsWith("track.richsync.get") }

    @Test
    fun missingInlineRichsyncFetchesByCommontrackIdAndEmitsTtml() = runBlocking {
        setup()
        val result = search().single()
        assertTrue(result.contains("<tt"))
        assertTrue(result.contains("Separate winner"))
        assertEquals("1001", richRequests().single().parameters["commontrack_id"])
    }

    @Test
    fun unavailableRichsyncMakesNoSeparateRequest() = runBlocking {
        setup(richAvailable = false)
        assertEquals(emptyList<String>(), search())
        assertTrue(richRequests().isEmpty())
    }

    @Test
    fun ordinaryStandaloneFailureKeepsOtherCandidates() = runBlocking {
        setup(secondCandidate = true, richStatuses = listOf(500))
        assertEquals(listOf("Second candidate"), search())
        assertEquals(1, richRequests().size)
    }

    @Test
    fun standaloneRichsyncWinsOverSubtitleAndPlainInBothPaths() = runBlocking {
        setup(subtitle = "Subtitle loser", plain = "Plain loser")
        val playback = Musixmatch.getLyrics("Song", "Artist", durationSeconds = 100).getOrThrow()
        val searched = search().single()
        assertEquals(playback, searched)
        assertTrue(playback.contains("Separate winner"))
        assertFalse(playback.contains("Subtitle loser"))
        assertFalse(playback.contains("Plain loser"))
    }

    @Test
    fun inlineRichsyncAvoidsExtraRequest() = runBlocking {
        setup(inline = richBody("Inline winner"))
        val searched = search().single()
        assertTrue(searched.contains("Inline winner"))
        assertTrue(richRequests().isEmpty())
    }

    @Test
    fun inlineRichsyncEmptyOrMalformedWithStatus200FallsBackToStandalone() = runBlocking {
        for (emptyBody in listOf("{}", "{\"richsync\":{}}", "{\"richsync\":{\"richsync_body\":\"\"}}", "{\"richsync\":{\"richsync_body\":\"invalid ttml\"}}")) {
            setup(inline = emptyBody)
            val searched = search().single()
            assertTrue(searched.contains("Separate winner"))
            assertEquals(1, richRequests().size)
        }
    }

    @Test
    fun standaloneFailureOrMalformedRichsyncRetainsSubtitleFallback() = runBlocking {
        setup(richStatuses = listOf(500), subtitle = "Subtitle winner")
        assertEquals(listOf("[00:01.000] Subtitle winner"), search())
        setup(separateBody = "{\"richsync\":{\"richsync_body\":\"not json\"}}", subtitle = "Subtitle winner")
        assertEquals(listOf("[00:01.000] Subtitle winner"), search())
    }

    @Test
    fun standaloneRateLimitIsTerminal() = runBlocking {
        setup(richStatuses = listOf(429))
        val failure = try { search(); null } catch (e: MusixmatchError) { e }
        assertTrue(failure is MusixmatchError.RateLimited)
        assertEquals(1, richRequests().size)
        assertFalse(requests.any { it.encodedPath.endsWith("token.get") })
    }

    @Test
    fun standaloneUnauthorizedRenewsOnceAndRetriesSameEndpoint() = runBlocking {
        setup(richStatuses = listOf(401, 200))
        assertTrue(search().single().contains("Separate winner"))
        assertEquals(2, richRequests().size)
        assertEquals(1, requests.count { it.encodedPath.endsWith("token.get") })
        assertEquals(listOf("cached_test_token", "renewed_test_token"), richRequests().map { it.parameters["usertoken"] })
    }

    @Test
    fun rejectedRenewedStandaloneTokenStopsOperation() = runBlocking {
        setup(richStatuses = listOf(401))
        val failure = try { search(); null } catch (e: MusixmatchError) { e }
        assertTrue(failure is MusixmatchError.TokenInvalid)
        assertEquals(2, richRequests().size)
        assertEquals(1, requests.count { it.encodedPath.endsWith("token.get") })
    }

    private fun quote(value: String) = Json.encodeToString(value)
    private fun track(id: Int, rich: Boolean): String =
        """{"track_id":$id,"commontrack_id":${id + 1000},"track_name":"Song","artist_name":"Artist","track_length":100,"has_richsync":${if (rich) 1 else 0}}"""
    private fun envelope(body: String, status: Int = 200) = """{"message":{"header":{"status_code":$status},"body":$body}}"""
    private fun richBody(text: String): String {
        val lines = """[{"ts":1.0,"te":3.0,"l":[{"c":${quote(text)},"o":0.0}],"x":${quote(text)}}]"""
        return """{"richsync":{"richsync_body":${quote(lines)}}}"""
    }
    private fun macro(id: Int, rich: Boolean, inline: String? = null, subtitle: String? = null, plain: String? = null): String {
        val subtitleBody = subtitle?.let {
            val lines = """[{"text":${quote(it)},"time":{"total":1.0}}]"""
            """{"subtitle_list":[{"subtitle":{"subtitle_body":${quote(lines)}}}]}"""
        }
        return envelope("""{"macro_calls":{
            "matcher.track.get":${envelope("{\"track\":${track(id,rich)}}")},
            "track.richsync.get":${envelope(inline ?: "{}", if (inline == null) 404 else 200)},
            "track.subtitles.get":${envelope(subtitleBody ?: "{}", if (subtitle == null) 404 else 200)},
            "track.lyrics.get":${envelope(plain?.let { "{\"lyrics\":{\"lyrics_body\":${quote(it)}}}" } ?: "{}", if (plain == null) 404 else 200)}
        }}""")
    }
}
