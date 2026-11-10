/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.unison

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.unison.models.UnisonListResponse
import moe.rukamori.archivetune.unison.models.UnisonLyrics
import moe.rukamori.archivetune.unison.models.UnisonResponse
import moe.rukamori.archivetune.unison.models.toLyrics

object Unison {
    private const val API_BASE_URL = "https://unison.betterlyrics.org/"
    private const val MAX_RESULTS = 5
    private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    private val KEY_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,128}$")

    private val jsonFormat by lazy {
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 20000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 20000
            }

            defaultRequest {
                url(API_BASE_URL)
            }

            expectSuccess = false
        }
    }

    var logger: ((String) -> Unit)? = null

    suspend fun getLyrics(
        videoId: String? = null,
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
    ): Result<UnisonLyrics> =
        try {
            val cleanVideoId = videoId.toVideoIdOrNull()
            val cleanTitle = title.trim()
            val cleanArtist = artist.trim()
            val hasMetadata = cleanTitle.isNotEmpty() && cleanArtist.isNotEmpty()
            require(cleanVideoId != null || hasMetadata) { "A video id or song title and artist are required" }

            val entry =
                cleanVideoId?.let { fetchByVideoId(it) }
                    ?: (if (hasMetadata) fetchByMetadata(cleanTitle, cleanArtist, album, durationSeconds) else null)
                    ?: throw IllegalStateException("Lyrics unavailable")
            logger?.invoke(
                "Unison got lyrics: id=${entry.id}, format=${entry.format}, syncType=${entry.syncType}, " +
                    "submitter=${entry.submitterName}",
            )
            Result.success(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun getAllLyrics(
        videoId: String? = null,
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
        callback: (UnisonLyrics) -> Unit,
    ) {
        val cleanVideoId = videoId.toVideoIdOrNull()
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val seenIds = HashSet<Long>()
        var delivered = 0

        suspend fun deliver(entries: List<UnisonLyrics>) {
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                if (delivered >= MAX_RESULTS) return
                if (!seenIds.add(entry.id)) continue
                delivered++
                logger?.invoke("Unison result $delivered: id=${entry.id}, format=${entry.format}, syncType=${entry.syncType}")
                callback(entry)
            }
        }

        if (cleanVideoId != null) deliver(fetchVariants(cleanVideoId))
        if (delivered < MAX_RESULTS && cleanTitle.isNotEmpty() && cleanArtist.isNotEmpty()) {
            deliver(searchByMetadata(cleanTitle, cleanArtist, album, durationSeconds))
        }
    }

    private suspend fun fetchByVideoId(videoId: String): UnisonLyrics? =
        fetchSingle("videoId") {
            url("lyrics")
            parameter("v", videoId)
        }

    private suspend fun fetchByMetadata(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int,
    ): UnisonLyrics? =
        fetchSingle("metadata") {
            url("lyrics")
            metadataParameters(title, artist, album, durationSeconds)
        }

    private suspend fun fetchVariants(videoId: String): List<UnisonLyrics> =
        fetchList("variants") {
            url("lyrics/variants/$videoId")
            parameter("limit", MAX_RESULTS)
        }

    private suspend fun searchByMetadata(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int,
    ): List<UnisonLyrics> =
        fetchList("search") {
            url("lyrics/search")
            metadataParameters(title, artist, album, durationSeconds)
            parameter("limit", MAX_RESULTS)
        }

    private fun HttpRequestBuilder.metadataParameters(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int,
    ) {
        parameter("song", title)
        parameter("artist", artist)
        album?.trim()?.takeIf { it.isNotEmpty() }?.let { parameter("album", it) }
        if (durationSeconds > 0) parameter("duration", durationSeconds)
    }

    private suspend fun fetchSingle(
        lookup: String,
        block: HttpRequestBuilder.() -> Unit,
    ): UnisonLyrics? {
        val body = fetchBody(lookup, block) ?: return null
        val parsed =
            runCatching { jsonFormat.decodeFromString<UnisonResponse>(body) }
                .onFailure { logger?.invoke("Unison $lookup parse error: ${it.message}") }
                .getOrNull()
                ?: return null
        if (!parsed.success) {
            logger?.invoke("Unison $lookup unsuccessful: code=${parsed.code}")
            return null
        }
        parsed.data?.matchedBy?.let { logger?.invoke("Unison $lookup matchedBy=$it") }
        return parsed.data?.toLyrics()
    }

    private suspend fun fetchList(
        lookup: String,
        block: HttpRequestBuilder.() -> Unit,
    ): List<UnisonLyrics> {
        val body = fetchBody(lookup, block) ?: return emptyList()
        val parsed =
            runCatching { jsonFormat.decodeFromString<UnisonListResponse>(body) }
                .onFailure { logger?.invoke("Unison $lookup parse error: ${it.message}") }
                .getOrNull()
                ?: return emptyList()
        if (!parsed.success) {
            logger?.invoke("Unison $lookup unsuccessful: code=${parsed.code}")
            return emptyList()
        }
        return parsed.data.orEmpty().mapNotNull { it.toLyrics() }
    }

    private suspend fun fetchBody(
        lookup: String,
        block: HttpRequestBuilder.() -> Unit,
    ): String? =
        try {
            val response = client.get(block)
            logger?.invoke("Unison $lookup response status: ${response.status}")
            when {
                response.status.isSuccess() -> response.bodyAsText()
                response.status == HttpStatusCode.NotFound -> null
                else -> {
                    logger?.invoke("Unison $lookup failed: ${response.status}")
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger?.invoke("Unison $lookup fetch error: ${e.message}")
            null
        }

    fun lyricsPageUrl(lyrics: UnisonLyrics): String? =
        lyrics.videoId.toVideoIdOrNull()?.let { videoId -> "${API_BASE_URL}song/$videoId?variantId=${lyrics.id}" }

    fun submitterPageUrl(lyrics: UnisonLyrics): String? =
        lyrics.submitterKeyId
            ?.takeIf { KEY_ID_REGEX.matches(it) }
            ?.let { keyId -> "${API_BASE_URL}curator/$keyId" }

    private fun String?.toVideoIdOrNull(): String? = this?.trim()?.takeIf { VIDEO_ID_REGEX.matches(it) }
}
