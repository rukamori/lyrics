/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.binimum

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

object BinimumLyrics {
    private const val LOOKUP_URL = "https://lyrics-api.binimum.org/"

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
            }
            expectSuccess = true
        }
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int,
    ): Result<String> =
        try {
            val match =
                lookup(title, artist, album, durationSeconds).firstOrNull()
                    ?: throw NoSuchElementException("Lyrics not found on Binimum Lyrics")
            Result.success(fetchTtml(match))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int,
        callback: (String) -> Unit,
    ) {
        lookup(title, artist, album, durationSeconds).forEach { match ->
            callback(fetchTtml(match))
        }
    }

    private suspend fun lookup(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int,
    ): List<TrackMatch> {
        require(title.isNotBlank() && artist.isNotBlank()) { "Song title and artist are required" }

        val response =
            client.get(LOOKUP_URL) {
                header("Accept", "application/json")
                parameter("track", title.trim())
                parameter("artist", artist.trim())
                album?.trim()?.takeIf { it.isNotEmpty() }?.let { parameter("album", it) }
                if (durationSeconds > 0) parameter("duration", durationSeconds)
            }
        return json
            .decodeFromString<LookupResponse>(response.bodyAsText())
            .results
            .sortedWith(
                compareByDescending<TrackMatch> { it.timingType == "word" }
                    .thenBy {
                        if (durationSeconds > 0 && it.duration != null) abs(it.duration - durationSeconds) else Double.MAX_VALUE
                    },
            ).distinctBy { it.lyricsUrl }
    }

    private suspend fun fetchTtml(match: TrackMatch): String {
        val url = Url(match.lyricsUrl)
        require(url.protocol.name == "https" && url.host.isNotBlank()) { "Invalid Binimum lyrics URL" }

        return client
            .get(url) {
                header("Accept", "application/ttml+xml, application/xml, text/xml")
            }.bodyAsText()
            .trim()
    }

    @Serializable
    private data class LookupResponse(
        val results: List<TrackMatch>,
    )

    @Serializable
    private data class TrackMatch(
        val lyricsUrl: String,
        @SerialName("timing_type") val timingType: String? = null,
        val duration: Double? = null,
    )
}
