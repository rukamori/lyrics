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
import org.junit.Test
import java.util.Collections

class MusixmatchSearchTest {

    @After
    fun tearDown() {
        Musixmatch.resetForTesting(null)
    }

    private fun jsonHeader() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun tokenResponse() = """
        {
          "message": {
            "header": {"status_code": 200},
            "body": {
              "user_token": "mock-token-search"
            }
          }
        }
    """.trimIndent()

    private fun macroForTrack(trackId: Long, name: String, lyrics: String) = """
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
                        "artist_name": "Artist",
                        "track_length": 200,
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

    @Test
    fun `getAllLyrics streams all successful candidates via callback`() = runBlocking {
        val searchResponse = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "track_list": [
                    {
                      "track": {
                        "track_id": 101,
                        "track_name": "Song Version 1",
                        "artist_name": "Artist",
                        "track_length": 200,
                        "has_lyrics": 1
                      }
                    },
                    {
                      "track": {
                        "track_id": 102,
                        "track_name": "Song Version 2",
                        "artist_name": "Artist",
                        "track_length": 200,
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
                        101L -> respond(macroForTrack(101, "Song Version 1", "Lyrics 1"), HttpStatusCode.OK, jsonHeader())
                        102L -> respond(macroForTrack(102, "Song Version 2", "Lyrics 2"), HttpStatusCode.OK, jsonHeader())
                        else -> respond("{}", HttpStatusCode.NotFound, jsonHeader())
                    }
                }
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeader())
            }
        }

        Musixmatch.resetForTesting(mockEngine)
        val emittedLyrics = Collections.synchronizedList(mutableListOf<String>())

        Musixmatch.getAllLyrics(title = "Song", artist = "Artist", durationSeconds = 200) { lyrics ->
            emittedLyrics.add(lyrics)
        }

        assertEquals(2, emittedLyrics.size)
        assertTrue(emittedLyrics.contains("Lyrics 1"))
        assertTrue(emittedLyrics.contains("Lyrics 2"))
    }

    @Test
    fun `getAllLyrics falls back to Title Matcher ONLY when candidate list is empty`() = runBlocking {
        // Search returns empty list
        val emptySearchResponse = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "track_list": []
                }
              }
            }
        """.trimIndent()

        var titleMatcherCalled = false
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("token.get") -> respond(tokenResponse(), HttpStatusCode.OK, jsonHeader())
                path.contains("track.search") -> respond(emptySearchResponse, HttpStatusCode.OK, jsonHeader())
                path.contains("macro.subtitles.get") -> {
                    // Title matcher by-name has no track_id param
                    if (request.url.parameters["track_id"] == null) {
                        titleMatcherCalled = true
                        respond(macroForTrack(999, "Song", "Title Matcher Lyrics"), HttpStatusCode.OK, jsonHeader())
                    } else {
                        respond("{}", HttpStatusCode.NotFound, jsonHeader())
                    }
                }
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeader())
            }
        }

        Musixmatch.resetForTesting(mockEngine)
        val emitted = mutableListOf<String>()

        Musixmatch.getAllLyrics(title = "Song", artist = "Artist", durationSeconds = 200) { lyrics ->
            emitted.add(lyrics)
        }

        assertTrue(titleMatcherCalled)
        assertEquals(1, emitted.size)
        assertEquals("Title Matcher Lyrics", emitted.first())
    }

    @Test
    fun `getAllLyrics does NOT trigger Title Matcher if search had candidates even when all fail`() = runBlocking {
        val searchWithCandidate = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "track_list": [
                    {
                      "track": {
                        "track_id": 201,
                        "track_name": "Song",
                        "artist_name": "Artist",
                        "track_length": 200,
                        "has_lyrics": 1
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        var titleMatcherCalled = false
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("token.get") -> respond(tokenResponse(), HttpStatusCode.OK, jsonHeader())
                path.contains("track.search") -> respond(searchWithCandidate, HttpStatusCode.OK, jsonHeader())
                path.contains("macro.subtitles.get") -> {
                    val trackId = request.url.parameters["track_id"]
                    if (trackId == null) {
                        titleMatcherCalled = true
                    }
                    // Fail resolving candidate 201
                    respond("{}", HttpStatusCode.NotFound, jsonHeader())
                }
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeader())
            }
        }

        Musixmatch.resetForTesting(mockEngine)
        val emitted = mutableListOf<String>()

        Musixmatch.getAllLyrics(title = "Song", artist = "Artist", durationSeconds = 200) { lyrics ->
            emitted.add(lyrics)
        }

        assertFalse(titleMatcherCalled)
        assertEquals(0, emitted.size)
    }

    @Test
    fun `getAllLyrics skips instrumental candidate quietly`() = runBlocking {
        val searchResponse = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "track_list": [
                    {
                      "track": {
                        "track_id": 301,
                        "track_name": "Beat",
                        "artist_name": "Producer",
                        "track_length": 200,
                        "has_lyrics": 1
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val instrumentalMacro = """
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
                            "track_id": 301,
                            "track_name": "Beat",
                            "artist_name": "Producer",
                            "instrumental": 1
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("token.get") -> respond(tokenResponse(), HttpStatusCode.OK, jsonHeader())
                path.contains("track.search") -> respond(searchResponse, HttpStatusCode.OK, jsonHeader())
                path.contains("macro.subtitles.get") -> respond(instrumentalMacro, HttpStatusCode.OK, jsonHeader())
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeader())
            }
        }

        Musixmatch.resetForTesting(mockEngine)
        val emitted = mutableListOf<String>()

        Musixmatch.getAllLyrics(title = "Beat", artist = "Producer") { lyrics ->
            emitted.add(lyrics)
        }

        assertEquals(0, emitted.size)
    }
}
