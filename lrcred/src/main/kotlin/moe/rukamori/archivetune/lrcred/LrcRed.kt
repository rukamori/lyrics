/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.lrcred

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer
import kotlin.math.abs

object LrcRed {
    private const val BASE_URL = "https://lrc.red/"
    private const val DEFAULT_CONTACT = "https://github.com/rukamori/ArchiveTune; me@miuka.moe"
    private const val MAX_DURATION_DELTA_SECONDS = 3.0
    private const val MAX_RESULTS = 3

    private val isrcRegex = Regex("^[A-Z0-9]{12}$")
    private val bracketRegex = Regex("""\s*[(\[【][^)\]】]*[)\]】]""")
    private val featuringRegex = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val artistSeparatorRegex =
        Regex("""\s*(?:,|&|/|\band\b|\bx\b|\bwith\b|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE)
    private val nonWordRegex = Regex("""[^\p{L}\p{N}]+""")
    private val diacriticsRegex = Regex("""\p{Mn}+""")

    @Volatile
    var userAgent: String = "ArchiveTune (+$DEFAULT_CONTACT)"
        private set

    fun setUserAgent(
        appName: String,
        versionName: String,
        contact: String = DEFAULT_CONTACT,
    ) {
        userAgent = "$appName/$versionName (+$contact)"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
            defaultRequest {
                url(BASE_URL)
            }
            expectSuccess = true
        }
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> =
        runSuspendCatching {
            val match =
                search(title, artist, durationSeconds).firstOrNull()
                    ?: throw NoSuchElementException("Lyrics not found on lrc.red")
            fetchTtml(match.isrc)
        }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
        callback: (String) -> Unit,
    ) {
        search(title, artist, durationSeconds).take(MAX_RESULTS).forEach { match ->
            callback(fetchTtml(match.isrc))
        }
    }

    private suspend fun search(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): List<SearchHit> {
        val queryTitle = title.trim()
        val queryArtist = artist.trim()
        require(queryTitle.isNotEmpty() && queryArtist.isNotEmpty()) { "Song title and artist are required" }

        val response =
            client.get("search.json") {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/json")
                parameter("q", "${cleanTitle(queryTitle)} ${primaryArtist(queryArtist)}".trim())
            }
        val hits = json.decodeFromString<SearchResponse>(response.bodyAsText()).hits

        val wantedTitle = normalize(cleanTitle(queryTitle))
        val wantedArtists = splitArtists(queryArtist)
        if (wantedTitle.isEmpty() || wantedArtists.isEmpty()) return emptyList()

        return hits
            .asSequence()
            .filter { isrcRegex.matches(it.isrc) }
            .mapNotNull { hit ->
                val titleScore = titleScore(wantedTitle, normalize(cleanTitle(hit.title)))
                if (titleScore == 0 || !artistMatches(wantedArtists, hit.artist)) return@mapNotNull null
                val delta =
                    if (durationSeconds > 0 && hit.duration != null) abs(hit.duration - durationSeconds) else null
                if (delta != null && delta > MAX_DURATION_DELTA_SECONDS) return@mapNotNull null
                RankedHit(hit, titleScore, delta ?: Double.MAX_VALUE)
            }.sortedWith(
                compareByDescending<RankedHit> { it.titleScore }
                    .thenBy { it.hit.byModel }
                    .thenBy { it.durationDelta },
            ).map { it.hit }
            .distinctBy { it.isrc }
            .toList()
    }

    private suspend fun fetchTtml(isrc: String): String {
        require(isrcRegex.matches(isrc)) { "Invalid lrc.red ISRC" }
        return client
            .get("s/$isrc.ttml") {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/ttml+xml, application/xml, text/xml")
            }.bodyAsText()
            .trim()
    }

    private fun titleScore(
        wanted: String,
        candidate: String,
    ): Int =
        when {
            candidate.isEmpty() -> 0
            candidate == wanted -> 2
            candidate.contains(wanted) || wanted.contains(candidate) -> 1
            else -> 0
        }

    private fun artistMatches(
        wantedArtists: List<String>,
        candidateArtist: String,
    ): Boolean {
        val candidates = splitArtists(candidateArtist)
        val joinedCandidate = normalize(candidateArtist)
        return wantedArtists.any { wanted ->
            joinedCandidate.contains(wanted) ||
                candidates.any { candidate -> candidate.contains(wanted) || wanted.contains(candidate) }
        }
    }

    private fun primaryArtist(artist: String): String =
        artist.split(artistSeparatorRegex).firstOrNull { it.isNotBlank() }?.trim() ?: artist

    private fun splitArtists(artist: String): List<String> =
        artist
            .split(artistSeparatorRegex)
            .map(::normalize)
            .filter { it.isNotEmpty() }
            .distinct()

    private fun cleanTitle(title: String): String =
        title
            .replace(bracketRegex, "")
            .replace(featuringRegex, "")
            .substringBefore(" - ")
            .trim()
            .ifEmpty { title.trim() }

    private fun normalize(value: String): String =
        Normalizer
            .normalize(value, Normalizer.Form.NFKD)
            .replace(diacriticsRegex, "")
            .lowercase()
            .replace(nonWordRegex, "")

    private suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private data class RankedHit(
        val hit: SearchHit,
        val titleScore: Int,
        val durationDelta: Double,
    )

    @Serializable
    private data class SearchResponse(
        val hits: List<SearchHit> = emptyList(),
    )

    @Serializable
    private data class SearchHit(
        val isrc: String,
        val title: String,
        val artist: String,
        val duration: Double? = null,
        val byModel: Boolean = false,
    )
}
