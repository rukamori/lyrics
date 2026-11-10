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
import org.junit.Assert.assertTrue
import org.junit.Test

class MusixmatchPlaybackTest {

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
              "user_token": "mock-token-playback"
            }
          }
        }
    """.trimIndent()

    @Test
    fun `getLyrics succeeds with primary matcher lyrics`() = runBlocking {
        val macroResponse = """
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
                            "track_id": 100,
                            "track_name": "Shape of You",
                            "artist_name": "Ed Sheeran",
                            "track_length": 233,
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
                            "lyrics_id": 101,
                            "lyrics_body": "The club isn't the best place to find a lover"
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
                path.contains("macro.subtitles.get") -> respond(macroResponse, HttpStatusCode.OK, jsonHeader())
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeader())
            }
        }

        Musixmatch.resetForTesting(mockEngine)
        val result = Musixmatch.getLyrics(title = "Shape of You", artist = "Ed Sheeran", durationSeconds = 233)

        assertTrue(result.isSuccess)
        assertEquals("The club isn't the best place to find a lover", result.getOrNull())
    }

    @Test
    fun `getLyrics returns instrumental note when primary matcher track is instrumental`() = runBlocking {
        val macroResponse = """
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
                            "track_id": 200,
                            "track_name": "Lofi Chill Beat",
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
                path.contains("macro.subtitles.get") -> respond(macroResponse, HttpStatusCode.OK, jsonHeader())
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeader())
            }
        }

        Musixmatch.resetForTesting(mockEngine)
        val result = Musixmatch.getLyrics(title = "Lofi Chill Beat", artist = "Producer")

        assertTrue(result.isSuccess)
        assertEquals("♪ Instrumental ♪", result.getOrNull())
    }

    @Test
    fun `getLyrics fails immediately on Restricted without fallback search`() = runBlocking {
        val macroResponse = """
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
                            "track_id": 300,
                            "track_name": "Copyrighted Song",
                            "artist_name": "Famous Artist",
                            "restricted": 1
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        var searchCalled = false
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("token.get") -> respond(tokenResponse(), HttpStatusCode.OK, jsonHeader())
                path.contains("macro.subtitles.get") -> respond(macroResponse, HttpStatusCode.OK, jsonHeader())
                path.contains("track.search") -> {
                    searchCalled = true
                    respond("{}", HttpStatusCode.OK, jsonHeader())
                }
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeader())
            }
        }

        Musixmatch.resetForTesting(mockEngine)
        val result = Musixmatch.getLyrics(title = "Copyrighted Song", artist = "Famous Artist")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MusixmatchError.Restricted)
        assertEquals(false, searchCalled)
    }

    @Test
    fun `getLyrics falls back to track search and picks demerit 0 over demerit 1`() = runBlocking {
        // Primary matcher fails with 404 NoMatch
        val primaryMatcherFail = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "macro_calls": {
                    "matcher.track.get": {
                      "message": {
                        "header": {"status_code": 404}
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        // track.search returns two candidates:
        // Candidate 1: Remix (demerit = 1), trackId 501
        // Candidate 2: Original (demerit = 0), trackId 502
        val searchResponse = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "track_list": [
                    {
                      "track": {
                        "track_id": 501,
                        "track_name": "Song (Remix)",
                        "artist_name": "Artist",
                        "track_length": 200,
                        "track_rating": 99,
                        "has_lyrics": 1
                      }
                    },
                    {
                      "track": {
                        "track_id": 502,
                        "track_name": "Song",
                        "artist_name": "Artist",
                        "track_length": 200,
                        "track_rating": 80,
                        "has_lyrics": 1
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        fun macroForTrack(trackId: Long, name: String, lyrics: String) = """
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

        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("token.get") -> respond(tokenResponse(), HttpStatusCode.OK, jsonHeader())
                path.contains("track.search") -> respond(searchResponse, HttpStatusCode.OK, jsonHeader())
                path.contains("macro.subtitles.get") -> {
                    val trackId = request.url.parameters["track_id"]?.toLongOrNull()
                    when (trackId) {
                        501L -> respond(macroForTrack(501, "Song (Remix)", "Remix Lyrics"), HttpStatusCode.OK, jsonHeader())
                        502L -> respond(macroForTrack(502, "Song", "Original Lyrics"), HttpStatusCode.OK, jsonHeader())
                        else -> respond(primaryMatcherFail, HttpStatusCode.OK, jsonHeader())
                    }
                }
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeader())
            }
        }

        Musixmatch.resetForTesting(mockEngine)
        val result = Musixmatch.getLyrics(title = "Song", artist = "Artist", durationSeconds = 200)

        assertTrue(result.isSuccess)
        // Original (demerit = 0) must win over Remix (demerit = 1) even though Remix had higher track_rating (99 vs 80)
        assertEquals("Original Lyrics", result.getOrNull())
    }
}
