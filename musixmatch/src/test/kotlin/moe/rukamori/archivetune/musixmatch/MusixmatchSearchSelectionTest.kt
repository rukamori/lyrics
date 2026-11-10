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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class MusixmatchSearchSelectionTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @After
    fun tearDown() {
        Musixmatch.resetForTesting(null)
        Musixmatch.logger = null
    }

    @Test
    fun searchFetchesOnlyFirstFourEligibleCandidatesById() = runBlocking {
        val result = search((1..5).map { track(it) })

        assertEquals(setOf("Candidate 1", "Candidate 2", "Candidate 3", "Candidate 4"), result.lyrics.toSet())
        assertEquals(4, result.lyrics.size)
        assertRouting(result, setOf(1, 2, 3, 4))
        val parameters = result.requests.single { it.encodedPath.endsWith("track.search") }.parameters
        assertEquals("20", parameters["page_size"])
        assertEquals("1", parameters["f_has_lyrics"])
        assertEquals("Song", parameters["q_track"])
        assertEquals("Artist", parameters["q_artist"])
        assertNull(parameters["q_artists"])
    }

    @Test
    fun eligibleCandidateAfterFifthResponseEntryStillGetsFetched() = runBlocking {
        val result = search(
            (1..6).map { track(it, length = 109) } + track(7, length = 100),
            duration = 100,
        )
        assertEquals(listOf("Candidate 7"), result.lyrics)
        assertRouting(result, setOf(7))
    }

    @Test
    fun filteringAndDeduplicationHappenBeforeSelectionCap() = runBlocking {
        val result = search(
            listOf(
                "null",
                "{}",
                track(0),
                track(-4),
                track(10, instrumental = 1),
                track(11, length = 109),
                track(1),
                track(1),
                track(2),
                track(3),
                track(4),
                track(5),
            ),
            duration = 100,
        )

        assertEquals(setOf("Candidate 1", "Candidate 2", "Candidate 3", "Candidate 4"), result.lyrics.toSet())
        assertEquals(4, result.lyrics.size)
        assertRouting(result, setOf(1, 2, 3, 4))
    }

    @Test
    fun durationDifferenceOfEightPassesInBothDirectionsButNineFails() = runBlocking {
        val result = search(
            listOf(track(1, length = 108), track(2, length = 92), track(3, length = 109), track(4, length = 91)),
            duration = 100,
        )

        assertEquals(setOf("Candidate 1", "Candidate 2"), result.lyrics.toSet())
        assertRouting(result, setOf(1, 2))
    }

    @Test
    fun unknownInputDurationSkipsDurationFilteringAtSearchAndMatcher() = runBlocking {
        val result = search(
            listOf(track(1, length = 900), track(2, length = 1)),
            duration = -1,
            macros = mapOf(1 to macro(1, matcherTrack = track(1, length = 1500))),
        )

        assertEquals(setOf("Candidate 1", "Candidate 2"), result.lyrics.toSet())
        assertRouting(result, setOf(1, 2))
    }

    @Test
    fun unknownCandidateAndMatcherDurationsSkipDurationFiltering() = runBlocking {
        val result = search(
            listOf(track(1, length = null), track(2, length = 0), track(3, length = -1)),
            duration = 100,
            macros = mapOf(
                1 to macro(1, matcherTrack = track(1, length = null)),
                2 to macro(2, matcherTrack = track(2, length = 0)),
                3 to macro(3, matcherTrack = track(3, length = -1)),
            ),
        )

        assertEquals(setOf("Candidate 1", "Candidate 2", "Candidate 3"), result.lyrics.toSet())
        assertRouting(result, setOf(1, 2, 3))
    }

    @Test
    fun matchingIdAcceptsDifferentMatcherTitleAndArtist() = runBlocking {
        val result = search(
            listOf(track(1)),
            macros = mapOf(1 to macro(1, matcherTrack = track(1, title = "Completely different", artist = "Someone else"))),
        )

        assertEquals(listOf("Candidate 1"), result.lyrics)
        assertRouting(result, setOf(1))
    }

    @Test
    fun matcherMustRetainSelectedIdStatusAndEligibleMetadata() = runBlocking {
        val rejected = listOf(
            macro(1, matcherStatus = 404),
            macro(1, matcherTrack = track(999)),
            macro(1, matcherTrack = track(1, instrumental = 1)),
            macro(1, matcherTrack = track(1, length = 109)),
        )
        for (response in rejected) {
            val result = search(
                listOf(track(1), track(2)),
                duration = 100,
                macros = mapOf(1 to response),
            )

            assertEquals(listOf("Candidate 2"), result.lyrics)
            assertRouting(result, setOf(1, 2))
        }
    }

    @Test
    fun matcherDurationDifferenceOfEightRemainsEligible() = runBlocking {
        val result = search(
            listOf(track(1), track(2)),
            duration = 100,
            macros = mapOf(
                1 to macro(1, matcherTrack = track(1, length = 108)),
                2 to macro(2, matcherTrack = track(2, length = 92)),
            ),
        )

        assertEquals(setOf("Candidate 1", "Candidate 2"), result.lyrics.toSet())
        assertRouting(result, setOf(1, 2))
    }

    @Test
    fun restrictedLyricsRejectEvenValidRichsyncBeforeConversion() = runBlocking {
        // Include non-200 lyrics metadata: a decodable restriction still applies.
        for (lyricsStatus in listOf(200, 404)) {
            val result = search(
                listOf(track(1), track(2)),
                macros = mapOf(
                    1 to macro(
                        1,
                        richsync = call(richsyncBody("Restricted words")),
                        lyrics = call(lyricsBody("Restricted plain", restricted = 1), lyricsStatus),
                    ),
                ),
            )

            assertEquals(listOf("Candidate 2"), result.lyrics)
            assertRouting(result, setOf(1, 2))
        }
    }

    @Test
    fun validRichsyncUsesProductionTtmlInsteadOfSubtitleOrPlain() = runBlocking {
        val result = search(
            listOf(track(1)),
            macros = mapOf(
                1 to macro(
                    1,
                    richsync = call(richsyncBody("Richsync winner")),
                    subtitles = call(subtitleBody("Subtitle loser")),
                ),
            ),
        )

        assertEquals(1, result.lyrics.size)
        assertTrue(result.lyrics.single().contains("<tt"))
        assertTrue(result.lyrics.single().contains("Richsync winner"))
        assertFalse(result.lyrics.single().contains("Subtitle loser"))
        assertRouting(result, setOf(1))
    }

    @Test
    fun invalidOrEmptyRichsyncFallsBackToProductionSubtitleLrc() = runBlocking {
        for (richsyncText in listOf("not json", "[]", "")) {
            val result = search(
                listOf(track(1)),
                macros = mapOf(
                    1 to macro(
                        1,
                        richsync = call("""{"richsync":{"richsync_body":${quote(richsyncText)}}}"""),
                        subtitles = call(subtitleBody("Subtitle winner")),
                    ),
                ),
            )

            assertEquals(listOf("[00:05.500] Subtitle winner"), result.lyrics)
            assertRouting(result, setOf(1))
        }
    }

    @Test
    fun invalidRichsyncAndSubtitleFallBackToCleanedPlainLyrics() = runBlocking {
        val result = search(
            listOf(track(1)),
            macros = mapOf(
                1 to macro(
                    1,
                    richsync = call("""{"richsync":{"richsync_body":"not json"}}"""),
                    subtitles = call("""{"subtitle_list":[{"subtitle":{"subtitle_body":"not json"}}]}"""),
                    lyrics = call(lyricsBody("Plain winner\n******* This Lyrics is NOT for Commercial use *******")),
                ),
            ),
        )

        assertEquals(listOf("Plain winner"), result.lyrics)
        assertRouting(result, setOf(1))
    }

    @Test
    fun unsuccessfulFormatSubcallsAreIgnoredDespiteUsableBodies() = runBlocking {
        val result = search(
            listOf(track(1), track(2), track(3)),
            macros = mapOf(
                1 to macro(
                    1,
                    richsync = call(richsyncBody("Failed richsync"), 500),
                    subtitles = call(subtitleBody("Subtitle winner")),
                ),
                2 to macro(
                    2,
                    richsync = call(richsyncBody("Failed richsync"), 404),
                    subtitles = call(subtitleBody("Failed subtitle"), 500),
                ),
                3 to macro(3, lyrics = call(lyricsBody("Failed plain"), 404)),
            ),
        )

        assertEquals(setOf("[00:05.500] Subtitle winner", "Candidate 2"), result.lyrics.toSet())
        assertEquals(2, result.lyrics.size)
        assertRouting(result, setOf(1, 2, 3))
    }

    @Test
    fun arrayNullAndAbsentFormatBodiesAllowOtherFormatsAndCandidates() = runBlocking {
        for (body in listOf("[]", "null", null)) {
            val result = search(
                listOf(track(1), track(2), track(3)),
                macros = mapOf(
                    1 to macro(1, richsync = call(body), subtitles = call(subtitleBody("Subtitle winner"))),
                    2 to macro(2, richsync = call(body), subtitles = call(body)),
                    3 to macro(3, richsync = call(body), subtitles = call(body), lyrics = call(body)),
                ),
            )

            assertEquals(setOf("[00:05.500] Subtitle winner", "Candidate 2"), result.lyrics.toSet())
            assertEquals(2, result.lyrics.size)
            assertRouting(result, setOf(1, 2, 3))
        }
    }

    @Test
    fun blankInputMakesNoRequestsOrCallbacks() = runBlocking {
        for ((title, artist) in listOf("   " to "Artist", "Song" to "\t", "" to "")) {
            val result = search(listOf(track(1)), title = title, artist = artist)
            assertTrue(result.lyrics.isEmpty())
            assertTrue(result.requests.isEmpty())
        }
    }

    @Test
    fun emptySearchOrEntirelyFilteredPoolAttemptsTitleMatcher() = runBlocking {
        for (tracks in listOf(emptyList(), listOf(track(0), track(1, instrumental = 1), track(2, length = 109)))) {
            val result = search(tracks, duration = 100)
            assertTrue(result.lyrics.isEmpty())
            val titleMacros = result.requests.filter { it.encodedPath.endsWith("macro.subtitles.get") }
            assertEquals(1, titleMacros.size)
            assertEquals(null, titleMacros.single().parameters["track_id"])
            assertEquals(2, result.requests.size)
        }
    }

    private data class SearchResult(val lyrics: List<String>, val requests: List<Url>)

    private suspend fun search(
        tracks: List<String>,
        duration: Int = -1,
        macros: Map<Int, String> = emptyMap(),
        title: String = " Song ",
        artist: String = " Artist ",
    ): SearchResult {
        val requests = Collections.synchronizedList(mutableListOf<Url>())
        Musixmatch.resetForTesting(MockEngine { request ->
            requests.add(request.url)
            when {
                request.url.encodedPath.endsWith("track.search") -> respond(
                    envelope("""{"track_list":[${tracks.joinToString(",") { """{"track":$it}""" }}]}"""),
                    headers = jsonHeaders,
                )
                request.url.encodedPath.endsWith("macro.subtitles.get") -> {
                    val id = request.url.parameters["track_id"]?.toIntOrNull() ?: 0
                    respond(macros[id] ?: macro(id, matcherStatus = if (id == 0) 404 else 200), headers = jsonHeaders)
                }
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        })
        Musixmatch.cachedToken = "selection_test_token"
        val lyrics = mutableListOf<String>()
        Musixmatch.getAllLyrics(title, artist, "Unused album", duration, lyrics::add)
        return SearchResult(lyrics.toList(), requests.toList())
    }

    // Assertions stay outside MockEngine: candidate error handling must never swallow them.
    private fun assertRouting(result: SearchResult, expectedIds: Set<Int>) {
        assertEquals(1, result.requests.count { it.encodedPath.endsWith("track.search") })
        val macros = result.requests.filter { it.encodedPath.endsWith("macro.subtitles.get") }
        assertEquals(expectedIds.size, macros.size)
        assertEquals(expectedIds, macros.map { it.parameters["track_id"]?.toIntOrNull() }.toSet())
        assertEquals(1 + expectedIds.size, result.requests.size)
        for (request in macros) {
            for (parameter in listOf("q_track", "q_artist", "q_artists", "q_album", "q_duration", "f_subtitle_length", "f_subtitle_length_max_deviation")) {
                assertFalse("ID macro must omit $parameter", request.parameters.contains(parameter))
            }
        }
        assertFalse(result.requests.any { it.encodedPath.endsWith("track.richsync.get") })
        assertFalse(result.requests.any { it.encodedPath.endsWith("track.subtitle.get") })
    }

    private fun quote(value: String): String = Json.encodeToString(value)

    private fun track(
        id: Int,
        length: Int? = 100,
        instrumental: Int = 0,
        title: String = "Song",
        artist: String = "Artist",
        hasRichsync: Int = 0,
        hasSubtitles: Int = 0,
    ): String {
        val duration = length?.let { ",\"track_length\":$it" } ?: ""
        return """{"track_id":$id,"commontrack_id":${id + 1000},"track_name":${quote(title)},"artist_name":${quote(artist)},"instrumental":$instrumental,"has_richsync":$hasRichsync,"has_subtitles":$hasSubtitles,"has_lyrics":1$duration}"""
    }

    private fun envelope(body: String): String =
        """{"message":{"header":{"status_code":200},"body":$body}}"""

    private fun call(body: String? = null, status: Int = 200): String =
        """{"message":{"header":{"status_code":$status}${body?.let { ",\"body\":$it" } ?: ""}}}"""

    private fun lyricsBody(text: String, restricted: Int = 0): String =
        """{"lyrics":{"lyrics_body":${quote(text)},"restricted":$restricted}}"""

    private fun subtitleBody(text: String): String {
        val subtitles = """[{"text":${quote(text)},"time":{"total":5.5}}]"""
        return """{"subtitle_list":[{"subtitle":{"subtitle_body":${quote(subtitles)}}}]}"""
    }

    private fun richsyncBody(text: String): String {
        val richsync = """[{"ts":1.0,"te":3.0,"l":[{"c":${quote(text)},"o":0.0}],"x":${quote(text)}}]"""
        return """{"richsync":{"richsync_body":${quote(richsync)}}}"""
    }

    private fun macro(
        id: Int,
        matcherTrack: String = track(id),
        matcherStatus: Int = 200,
        richsync: String = call(),
        subtitles: String = call(),
        lyrics: String = call(lyricsBody("Candidate $id")),
    ): String = envelope(
        """{"macro_calls":{"matcher.track.get":${call("""{"track":$matcherTrack}""", matcherStatus)},"track.richsync.get":$richsync,"track.subtitles.get":$subtitles,"track.lyrics.get":$lyrics}}""",
    )
}
