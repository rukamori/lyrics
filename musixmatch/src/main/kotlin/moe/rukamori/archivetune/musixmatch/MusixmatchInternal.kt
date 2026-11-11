/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.musixmatch.models.ApiResponse
import moe.rukamori.archivetune.musixmatch.models.RichSyncBody
import moe.rukamori.archivetune.musixmatch.models.SubtitleBody
import moe.rukamori.archivetune.musixmatch.models.Track

internal const val BASE_URL = "https://apic-appmobile.musixmatch.com/ws/1.1/"
internal const val APP_ID = "mac-ios-v2.0"

sealed interface MacroTarget {
    data class ByName(
        val title: String,
        val artist: String,
        val album: String = "",
        val durationSeconds: Int = -1,
    ) : MacroTarget

    data class ById(
        val trackId: Long,
        val title: String = "",
        val artist: String = "",
        val durationSeconds: Int = -1,
    ) : MacroTarget
}

data class ResolvedLyrics(
    val lyrics: String,
    val quality: Int = 0,
    val terminalError: MusixmatchError? = null,
)

data class SearchCandidate(
    val track: Track,
    val demerit: Int,
)

internal sealed interface CandidateOutcome {
    val candidate: SearchCandidate
    val rank: Int

    data class Success(
        override val candidate: SearchCandidate,
        override val rank: Int,
        val lyrics: ResolvedLyrics,
    ) : CandidateOutcome

    data class TerminalFailure(
        override val candidate: SearchCandidate,
        override val rank: Int,
        val error: MusixmatchError,
    ) : CandidateOutcome

    data class LocalFailure(
        override val candidate: SearchCandidate,
        override val rank: Int,
        val error: Throwable,
    ) : CandidateOutcome
}

internal fun errorLabel(e: Throwable): String =
    if (e is MusixmatchError) e::class.simpleName ?: "MusixmatchError"
    else "OtherException"

internal suspend inline fun <reified T> decodeApiResponse(
    response: HttpResponse,
    jsonFormat: Json,
    operation: String,
): T = checkNotNull(decodeResponse<T>(response, jsonFormat, operation, lenient = false))

internal suspend inline fun <reified T> decodeStandaloneResponse(
    response: HttpResponse,
    jsonFormat: Json,
    operation: String,
): T? = decodeResponse<T>(response, jsonFormat, operation, lenient = true)

internal suspend inline fun <reified T> decodeResponse(
    response: HttpResponse,
    jsonFormat: Json,
    operation: String,
    lenient: Boolean,
): T? {
    if (response.status == HttpStatusCode.Unauthorized) throw MusixmatchError.TokenInvalid("HTTP 401 on $operation")
    if (response.status == HttpStatusCode.TooManyRequests) throw MusixmatchError.RateLimited("HTTP 429 on $operation")
    if (!response.status.isSuccess()) {
        if (lenient) return null
        if (response.status.value >= 500) throw MusixmatchError.ServerError(response.status.value)
        throw MusixmatchError.MalformedResponse("HTTP ${response.status.value} on $operation")
    }

    val bodyText = response.bodyAsText()
    val apiResponse = runCatching { jsonFormat.decodeFromString<ApiResponse<T>>(bodyText) }
        .getOrElse {
            if (lenient) return null
            throw MusixmatchError.MalformedResponse("Failed to parse $operation response")
        }
    val statusCode = apiResponse.message?.header?.statusCode ?: 0
    if (statusCode == 401) throw MusixmatchError.TokenInvalid("Status code 401 on $operation")
    if (statusCode == 429) throw MusixmatchError.RateLimited("Status code 429 on $operation")
    if (statusCode != 200) {
        if (lenient) return null
        if (statusCode >= 500) throw MusixmatchError.ServerError(statusCode)
        throw MusixmatchError.MalformedResponse("Status code $statusCode on $operation")
    }
    return apiResponse.message?.body ?: if (lenient) null else throw MusixmatchError.MalformedResponse("Empty $operation body")
}

internal suspend fun fetchRichSyncSeparately(
    client: HttpClient,
    jsonFormat: Json,
    commonTrackId: Long,
    trackLength: Int,
    token: String,
    contextLabel: String,
    logger: ((String) -> Unit)? = null,
): String? {
    if (commonTrackId <= 0) return null
    return try {
        logger?.invoke("Musixmatch $contextLabel: separate richsync request started")
        val response =
            client.get("${BASE_URL}track.richsync.get") {
                parameter("format", "json")
                parameter("subtitle_format", "mxm")
                parameter("app_id", APP_ID)
                parameter("richsync_compact_type", "words")
                parameter("commontrack_id", commonTrackId)
                parameter("f_subtitle_length", trackLength)
                parameter("q_duration", trackLength)
                parameter("usertoken", token)
            }
        decodeStandaloneResponse<RichSyncBody>(response, jsonFormat, "richsync")?.richsync?.richsyncBody
    } catch (e: CancellationException) {
        throw e
    } catch (e: MusixmatchError) {
        if (e is MusixmatchError.TokenInvalid || e is MusixmatchError.RateLimited) throw e
        null
    } catch (e: Exception) {
        null
    }
}

internal suspend fun fetchSubtitleSeparately(
    client: HttpClient,
    jsonFormat: Json,
    trackId: Long,
    trackLength: Int,
    token: String,
    contextLabel: String,
    logger: ((String) -> Unit)? = null,
): String? {
    if (trackId <= 0) return null
    return try {
        logger?.invoke("Musixmatch $contextLabel: separate subtitle request started")
        val response =
            client.get("${BASE_URL}track.subtitle.get") {
                parameter("format", "json")
                parameter("subtitle_format", "mxm")
                parameter("app_id", APP_ID)
                parameter("track_id", trackId)
                if (trackLength > 0) parameter("f_subtitle_length", trackLength)
                parameter("usertoken", token)
            }
        decodeStandaloneResponse<SubtitleBody>(response, jsonFormat, "subtitle")?.subtitle?.subtitleBody
    } catch (e: CancellationException) {
        throw e
    } catch (e: MusixmatchError) {
        if (e is MusixmatchError.TokenInvalid || e is MusixmatchError.RateLimited) throw e
        null
    } catch (e: Exception) {
        null
    }
}
