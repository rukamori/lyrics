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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MusixmatchResolverTest {

    @After
    fun tearDown() {
        Musixmatch.resetForTesting(null)
    }

    private fun jsonHeader() = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `resolveRequest returns RichSync TTML with quality 2 when inline richsync exists`() = runBlocking {
        val richsyncJson = """[{"ts":1.0,"te":3.0,"l":[{"c":"Hello","o":0.0}],"x":"Hello"}]"""
        val escapedRichSync = richsyncJson.replace("\"", "\\\"")

        val macroResponseBody = """
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
                            "track_id": 12345,
                            "track_name": "Test Song",
                            "artist_name": "Test Artist",
                            "track_length": 200,
                            "has_richsync": 1
                          }
                        }
                      }
                    },
                    "track.richsync.get": {
                      "message": {
                        "header": {"status_code": 200},
                        "body": {
                          "richsync": {
                            "richsync_id": 999,
                            "richsync_body": "$escapedRichSync"
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
            respond(macroResponseBody, HttpStatusCode.OK, jsonHeader())
        }

        Musixmatch.resetForTesting(mockEngine)
        val tokenState = SearchTokenState("valid-token") { "new-token" }

        val resolved = Musixmatch.resolveRequest(
            target = MacroTarget.ByName("Test Song", "Test Artist"),
            tokenState = tokenState,
            verifyTitle = "Test Song",
            verifyArtist = "Test Artist",
            verifyDuration = 200,
        )

        assertEquals(2, resolved.quality)
        assertTrue(resolved.lyrics.contains("<span begin=\"1.000s\" end=\"3.000s\">Hello</span>"))
    }

    @Test
    fun `resolveRequest falls back to Subtitle LRC with quality 1 when richsync is absent`() = runBlocking {
        val subtitleJson = """[{"text":"Line one","time":{"total":12.5}}]"""
        val escapedSubtitle = subtitleJson.replace("\"", "\\\"")

        val macroResponseBody = """
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
                            "track_id": 12345,
                            "track_name": "Test Song",
                            "artist_name": "Test Artist",
                            "track_length": 200,
                            "has_subtitles": 1
                          }
                        }
                      }
                    },
                    "track.subtitles.get": {
                      "message": {
                        "header": {"status_code": 200},
                        "body": {
                          "subtitle_list": [
                            {
                              "subtitle": {
                                "subtitle_id": 888,
                                "subtitle_body": "$escapedSubtitle"
                              }
                            }
                          ]
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            respond(macroResponseBody, HttpStatusCode.OK, jsonHeader())
        }

        Musixmatch.resetForTesting(mockEngine)
        val tokenState = SearchTokenState("valid-token") { "new-token" }

        val resolved = Musixmatch.resolveRequest(
            target = MacroTarget.ByName("Test Song", "Test Artist"),
            tokenState = tokenState,
            verifyTitle = "Test Song",
            verifyArtist = "Test Artist",
            verifyDuration = 200,
        )

        assertEquals(1, resolved.quality)
        assertEquals("[00:12.500] Line one", resolved.lyrics)
    }

    @Test
    fun `resolveRequest falls back to Plain lyrics with quality 0 when richsync and subtitle are absent`() = runBlocking {
        val plainLyrics = "Plain lyrics text here\nSecond line"

        val macroResponseBody = """
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
                            "track_id": 12345,
                            "track_name": "Test Song",
                            "artist_name": "Test Artist",
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
                            "lyrics_id": 777,
                            "lyrics_body": "$plainLyrics"
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
            respond(macroResponseBody, HttpStatusCode.OK, jsonHeader())
        }

        Musixmatch.resetForTesting(mockEngine)
        val tokenState = SearchTokenState("valid-token") { "new-token" }

        val resolved = Musixmatch.resolveRequest(
            target = MacroTarget.ByName("Test Song", "Test Artist"),
            tokenState = tokenState,
            verifyTitle = "Test Song",
            verifyArtist = "Test Artist",
            verifyDuration = 200,
        )

        assertEquals(0, resolved.quality)
        assertEquals("Plain lyrics text here\nSecond line", resolved.lyrics)
    }

    @Test
    fun `resolveRequest throws Restricted when track is restricted`() = runBlocking {
        val macroResponseBody = """
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
                            "track_id": 12345,
                            "track_name": "Restricted Song",
                            "artist_name": "Artist",
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

        val mockEngine = MockEngine { request ->
            respond(macroResponseBody, HttpStatusCode.OK, jsonHeader())
        }

        Musixmatch.resetForTesting(mockEngine)
        val tokenState = SearchTokenState("valid-token") { "new-token" }

        try {
            Musixmatch.resolveRequest(
                target = MacroTarget.ByName("Restricted Song", "Artist"),
                tokenState = tokenState,
            )
            fail("Expected Restricted to be thrown")
        } catch (e: MusixmatchError.Restricted) {
            assertNotNull(e)
        }
    }

    @Test
    fun `resolveRequest throws Instrumental when track is instrumental`() = runBlocking {
        val macroResponseBody = """
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
                            "track_id": 12345,
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
            respond(macroResponseBody, HttpStatusCode.OK, jsonHeader())
        }

        Musixmatch.resetForTesting(mockEngine)
        val tokenState = SearchTokenState("valid-token") { "new-token" }

        try {
            Musixmatch.resolveRequest(
                target = MacroTarget.ByName("Beat", "Producer"),
                tokenState = tokenState,
            )
            fail("Expected Instrumental to be thrown")
        } catch (e: MusixmatchError.Instrumental) {
            assertEquals("Track is instrumental", e.message)
        }
    }

    @Test
    fun `resolveRequest fetches standalone subtitle when inline fails but has_subtitles is 1`() = runBlocking {
        val macroResponseBody = """
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
                            "track_id": 12345,
                            "track_name": "Test Song",
                            "artist_name": "Test Artist",
                            "has_subtitles": 1
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val standaloneSubtitleBody = """
            {
              "message": {
                "header": {"status_code": 200},
                "body": {
                  "subtitle": {
                    "subtitle_id": 999,
                    "subtitle_body": "[00:15.000] Standalone subtitle line"
                  }
                }
              }
            }
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            val url = request.url.encodedPath
            if (url.contains("macro.subtitles.get")) {
                respond(macroResponseBody, HttpStatusCode.OK, jsonHeader())
            } else if (url.contains("track.subtitle.get")) {
                respond(standaloneSubtitleBody, HttpStatusCode.OK, jsonHeader())
            } else {
                respond("{}", HttpStatusCode.NotFound, jsonHeader())
            }
        }

        Musixmatch.resetForTesting(mockEngine)
        val tokenState = SearchTokenState("valid-token") { "new-token" }

        val resolved = Musixmatch.resolveRequest(
            target = MacroTarget.ByName("Test Song", "Test Artist"),
            tokenState = tokenState,
        )

        assertEquals(1, resolved.quality)
        assertEquals("[00:15.000] Standalone subtitle line", resolved.lyrics)
    }
}
