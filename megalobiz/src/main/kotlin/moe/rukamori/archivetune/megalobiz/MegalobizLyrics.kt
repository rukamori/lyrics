/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.megalobiz

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

object MegalobizLyrics {
    private const val BASE_URL = "https://www.megalobiz.com"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

    private val lrcPathRegex = Regex("""href=["'](/lrc/maker/download/[^"']+)["']""")
    private val lrcSpanRegex = Regex("""id=["']lrc_[^"']*_details["'][^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val htmlTagRegex = Regex("""<[^>]+>""")

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
    ): Result<String> =
        try {
            Result.success(
                fetchLyrics(title, artist) ?: throw NoSuchElementException("Lyrics not found on Megalobiz"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
    ): String? {
        val query = "$artist $title".trim()
        val searchHtml = fetchHtml("$BASE_URL/searchall") { parameter("qry", query) } ?: return null
        val match = lrcPathRegex.find(searchHtml) ?: return null
        val detailHtml = fetchHtml(BASE_URL + match.groupValues[1]) ?: return null
        val rawLrcText = lrcSpanRegex.find(detailHtml)?.groupValues?.get(1) ?: detailHtml

        return rawLrcText
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")
            .replace(htmlTagRegex, "")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchHtml(
        url: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): String? {
        val response =
            client.get(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                configure()
            }
        return if (response.status.isSuccess()) response.bodyAsText() else null
    }
}
