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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class MusixmatchSearchTest {
    @After
    fun tearDown() {
        Musixmatch.resetForTesting()
        Musixmatch.logger = null
    }

    @Test
    fun searchEmitsLyricsForEachSelectedTrack() = runBlocking {
        val headers = headersOf(HttpHeaders.ContentType, "application/json")
        Musixmatch.resetForTesting(MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("track.search") ->
                    """{"track_list":[{"track":{"track_id":1}},{"track":{"track_id":2}}]}"""
                request.url.encodedPath.endsWith("macro.subtitles.get") -> {
                    val id = request.url.parameters["track_id"]?.toLongOrNull() ?: 0
                    """{"macro_calls":{
                        "matcher.track.get":{"message":{"header":{"status_code":200},"body":{"track":{"track_id":$id,"track_name":"Song","artist_name":"Artist"}}}},
                        "track.lyrics.get":{"message":{"header":{"status_code":200},"body":{"lyrics":{"lyrics_body":"Lyrics for candidate $id"}}}}
                    }}"""
                }
                else -> error("Unexpected endpoint")
            }
            respond("""{"message":{"header":{"status_code":200},"body":$body}}""", headers = headers)
        })
        Musixmatch.cachedToken = "cached_test_token"
        val emitted = mutableListOf<String>()
        Musixmatch.getAllLyrics("Song", "Artist", callback = emitted::add)
        assertEquals(setOf("Lyrics for candidate 1", "Lyrics for candidate 2"), emitted.toSet())
        assertEquals(2, emitted.size)
    }
}
