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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class MusixmatchSearchConcurrencyTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val oldToken = "cached_search_token_123456"
    private val newToken = "renewed_search_token_123456"

    @After
    fun tearDown() {
        Musixmatch.resetForTesting()
        Musixmatch.logger = null
    }

    @Test
    fun twoMacroRequestsOverlapAndEmitBeforeBlockedCandidatesFinish() = scenario {
        val entered = Channel<Int>(Channel.UNLIMITED)
        val callbacks = Channel<String>(Channel.UNLIMITED)
        val gates = (1..4).associateWith { CompletableDeferred<Unit>() }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val emitted = ConcurrentLinkedQueue<String>()
        install(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("track.search") ->
                    respond(search(1, 2, 3, 4), headers = jsonHeaders)
                request.url.encodedPath.endsWith("macro.subtitles.get") -> {
                    val id = request.url.parameters["track_id"]!!.toInt()
                    val current = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, current) }
                    try {
                        entered.send(id)
                        gates.getValue(id).await()
                        respond(macro(id), headers = jsonHeaders)
                    } finally {
                        active.decrementAndGet()
                    }
                }
                else -> error("Unexpected endpoint")
            }
        })
        val operation = async {
            Musixmatch.getAllLyrics("Song", "Artist") {
                emitted.add(it)
                callbacks.trySend(it).getOrThrow()
            }
        }
        try {
            val first = entered.receive()
            val second = entered.receive()
            assertTrue(first != second)
            assertEquals(2, active.get())
            assertEquals(2, peak.get())
            assertTrue(entered.tryReceive().isFailure)
            assertTrue(emitted.isEmpty())

            gates.getValue(first).complete(Unit)
            assertEquals("Candidate $first", callbacks.receive())
            val third = entered.receive()
            assertTrue(third != first && third != second)
            assertFalse(operation.isCompleted)
            assertEquals(listOf("Candidate $first"), emitted.toList())
            assertEquals(2, peak.get())

            gates.values.forEach { it.complete(Unit) }
            operation.await()
            assertEquals(setOf("Candidate 1", "Candidate 2", "Candidate 3", "Candidate 4"), emitted.toSet())
            assertEquals(4, emitted.size)
            assertEquals(2, peak.get())
        } finally {
            cleanup(operation, *gates.values.toTypedArray())
            entered.close()
            callbacks.close()
        }
    }

    @Test
    fun parentCancellationStopsActiveFetchesAndLeavesPermitWaiterUnrequested() = scenario {
        val entered = Channel<Int>(Channel.UNLIMITED)
        val callbacks = Channel<String>(Channel.UNLIMITED)
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseOthers = CompletableDeferred<Unit>()
        val requested = ConcurrentLinkedQueue<Int>()
        val emitted = ConcurrentLinkedQueue<String>()
        install(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("track.search") ->
                    respond(search(1, 2, 3, 4), headers = jsonHeaders)
                request.url.encodedPath.endsWith("macro.subtitles.get") -> {
                    val id = request.url.parameters["track_id"]!!.toInt()
                    requested.add(id)
                    entered.send(id)
                    if (id == 1) releaseFirst.await() else releaseOthers.await()
                    respond(macro(id), headers = jsonHeaders)
                }
                else -> error("Unexpected endpoint")
            }
        })
        val operation = async {
            Musixmatch.getAllLyrics("Song", "Artist") {
                emitted.add(it)
                callbacks.trySend(it).getOrThrow()
            }
        }
        try {
            assertEquals(setOf(1, 2), setOf(entered.receive(), entered.receive()))
            releaseFirst.complete(Unit)
            assertEquals("Candidate 1", callbacks.receive())
            assertEquals(3, entered.receive())
            assertFalse(operation.isCompleted)

            operation.cancelAndJoin()
            assertTrue(operation.isCancelled)
            // Observe the production job completing while engine-scoped handlers stay blocked.
            assertEquals(setOf(1, 2, 3), requested.toSet())
            assertEquals(listOf("Candidate 1"), emitted.toList())
            assertTrue(entered.tryReceive().isFailure)
            releaseOthers.complete(Unit)
            assertEquals(listOf("Candidate 1"), emitted.toList())
        } finally {
            cleanup(operation, releaseFirst, releaseOthers)
            entered.close()
            callbacks.close()
        }
    }

    @Test
    fun callbackExceptionEscapesAndCancelsBlockedSibling() = scenario {
        val entered = Channel<Int>(Channel.UNLIMITED)
        val callbackStarted = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<Throwable?>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSibling = CompletableDeferred<Unit>()
        val callbackCount = AtomicInteger()
        val expected = IllegalStateException("callback failed")
        install(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("track.search") ->
                    respond(search(1, 2), headers = jsonHeaders)
                request.url.encodedPath.endsWith("macro.subtitles.get") -> {
                    val id = request.url.parameters["track_id"]!!.toInt()
                    entered.send(id)
                    if (id == 1) releaseFirst.await() else releaseSibling.await()
                    respond(macro(id), headers = jsonHeaders)
                }
                else -> error("Unexpected endpoint")
            }
        })
        val operation = async {
            Musixmatch.getAllLyrics("Song", "Artist") {
                if (callbackCount.incrementAndGet() == 1) {
                    callbackStarted.complete(Unit)
                    throw expected
                }
            }
        }
        operation.invokeOnCompletion { completion.complete(it) }
        try {
            assertEquals(setOf(1, 2), setOf(entered.receive(), entered.receive()))
            releaseFirst.complete(Unit)
            withTimeout(1_000) { callbackStarted.await() }
            releaseSibling.complete(Unit)
            val failure = withTimeout(1_000) { completion.await() }
            assertTrue(failure is IllegalStateException)
            assertEquals(expected.message, failure?.message)
            assertTrue(operation.isCancelled)
            assertEquals(1, callbackCount.get())
        } finally {
            cleanup(operation, releaseFirst, releaseSibling)
            entered.close()
        }
    }

    @Test
    fun callbackCancellationCancelsEntireSearch() = scenario {
        val callbacks = AtomicInteger()
        install(MockEngine { request ->
            if (request.url.encodedPath.endsWith("track.search")) {
                respond(search(1, 2, 3), headers = jsonHeaders)
            } else {
                respond(macro(request.url.parameters["track_id"]!!.toInt()), headers = jsonHeaders)
            }
        })
        val operation = async {
            Musixmatch.getAllLyrics("Song", "Artist") {
                callbacks.incrementAndGet()
                throw kotlinx.coroutines.CancellationException("Collector stopped")
            }
        }
        try {
            operation.join()
            assertTrue(operation.isCancelled)
            assertEquals(1, callbacks.get())
        } finally {
            cleanup(operation)
        }
    }

    @Test
    fun failedSearchDoesNotExposeNetworkExceptionSecrets() = scenario {
        val secret = "https://example.invalid/?usertoken=private-token"
        install(MockEngine { throw IOException(secret) })
        val error = failure { Musixmatch.getAllLyrics("Song", "Artist") {} }
        assertTrue(error is MusixmatchError)
        assertFalse(error.toString().contains(secret))
        assertEquals(null, error.cause)
    }

    @Test
    fun serverMalformedAndNetworkFailuresEachPreserveOtherCandidates() = scenario {
        for (kind in listOf("server", "malformed", "network")) {
                val requests = ConcurrentHashMap<Int, AtomicInteger>()
                val emitted = ConcurrentLinkedQueue<String>()
                install(MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("track.search") ->
                            respond(search(1, 2, 3), headers = jsonHeaders)
                        request.url.encodedPath.endsWith("macro.subtitles.get") -> {
                            val id = request.url.parameters["track_id"]!!.toInt()
                            requests.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
                            if (id != 1) {
                                respond(macro(id), headers = jsonHeaders)
                            } else {
                                when (kind) {
                                    "server" -> respond("", HttpStatusCode.ServiceUnavailable, jsonHeaders)
                                    "malformed" -> respond("{", headers = jsonHeaders)
                                    else -> throw IOException("candidate connection failed")
                                }
                            }
                        }
                        else -> error("Unexpected endpoint")
                    }
                })
                val operation = async {
                    Musixmatch.getAllLyrics("Song", "Artist", callback = { emitted.add(it) })
                }
                try {
                    operation.await()
                    assertEquals(kind, setOf("Candidate 2", "Candidate 3"), emitted.toSet())
                    assertEquals(kind, 2, emitted.size)
                    assertEquals(kind, mapOf(1 to 1, 2 to 1, 3 to 1), requests.mapValues { it.value.get() })
                } finally {
                    cleanup(operation)
                }
        }
    }
    @Test
    fun simultaneousMacroUnauthorizedResponsesShareOneRenewalAndEachRetryOnce() = scenario {
        val oldRequests = Channel<Int>(Channel.UNLIMITED)
        val releaseOldResponses = CompletableDeferred<Unit>()
        val renewalStarted = CompletableDeferred<Unit>()
        val releaseRenewal = CompletableDeferred<Unit>()
        val renewals = AtomicInteger()
        val requests = ConcurrentLinkedQueue<Pair<Int, String?>>()
        val emitted = ConcurrentLinkedQueue<String>()
        install(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("track.search") ->
                    respond(search(1, 2), headers = jsonHeaders)
                request.url.encodedPath.endsWith("token.get") -> {
                    renewals.incrementAndGet()
                    renewalStarted.complete(Unit)
                    releaseRenewal.await()
                    respond(token(), headers = jsonHeaders)
                }
                request.url.encodedPath.endsWith("macro.subtitles.get") -> {
                    val id = request.url.parameters["track_id"]!!.toInt()
                    val usedToken = request.url.parameters["usertoken"]
                    requests.add(id to usedToken)
                    if (usedToken == oldToken) {
                        oldRequests.send(id)
                        releaseOldResponses.await()
                        if (id == 1) {
                            respond("", HttpStatusCode.Unauthorized, jsonHeaders)
                        } else {
                            respond(status(401), headers = jsonHeaders)
                        }
                    } else {
                        respond(macro(id), headers = jsonHeaders)
                    }
                }
                else -> error("Unexpected endpoint")
            }
        })
        val operation = async {
            Musixmatch.getAllLyrics("Song", "Artist", callback = { emitted.add(it) })
        }
        try {
            assertEquals(setOf(1, 2), setOf(oldRequests.receive(), oldRequests.receive()))
            releaseOldResponses.complete(Unit)
            renewalStarted.await()
            assertEquals(1, renewals.get())
            releaseRenewal.complete(Unit)
            operation.await()
            assertEquals(1, renewals.get())
            assertEquals(setOf("Candidate 1", "Candidate 2"), emitted.toSet())
            assertEquals(2, emitted.size)
            assertEquals(
                mapOf((1 to oldToken) to 1, (2 to oldToken) to 1, (1 to newToken) to 1, (2 to newToken) to 1),
                requests.groupingBy { it }.eachCount(),
            )
        } finally {
            cleanup(operation, releaseOldResponses, releaseRenewal)
            oldRequests.close()
        }
    }

    @Test
    fun searchRenewalConsumesBudgetAndLaterMacroUnauthorizedIsFatal() = scenario {
        val searchTokens = ConcurrentLinkedQueue<String?>()
        val macroRequests = ConcurrentLinkedQueue<Pair<Int, String?>>()
        val entered = Channel<Int>(Channel.UNLIMITED)
        val releaseUnauthorized = CompletableDeferred<Unit>()
        val releaseSibling = CompletableDeferred<Unit>()
        val renewals = AtomicInteger()
        val emitted = ConcurrentLinkedQueue<String>()
        install(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("track.search") -> {
                    val usedToken = request.url.parameters["usertoken"]
                    searchTokens.add(usedToken)
                    if (usedToken == oldToken) respond(status(401), headers = jsonHeaders)
                    else respond(search(1, 2, 3), headers = jsonHeaders)
                }
                request.url.encodedPath.endsWith("token.get") -> {
                    renewals.incrementAndGet()
                    respond(token(), headers = jsonHeaders)
                }
                request.url.encodedPath.endsWith("macro.subtitles.get") -> {
                    val id = request.url.parameters["track_id"]!!.toInt()
                    macroRequests.add(id to request.url.parameters["usertoken"])
                    entered.send(id)
                    if (id == 1) {
                        releaseUnauthorized.await()
                        respond("", HttpStatusCode.Unauthorized, jsonHeaders)
                    } else {
                        releaseSibling.await()
                        respond(macro(id), headers = jsonHeaders)
                    }
                }
                else -> error("Unexpected endpoint")
            }
        })
        val operation = async {
            Musixmatch.getAllLyrics("Song", "Artist", callback = { emitted.add(it) })
        }
        try {
            assertEquals(setOf(1, 2), setOf(entered.receive(), entered.receive()))
            releaseUnauthorized.complete(Unit)
            releaseSibling.complete(Unit)
            assertTrue(failure { operation.await() } is MusixmatchError.TokenInvalid)
            assertEquals(listOf(oldToken, newToken), searchTokens.toList())
            assertEquals(1, renewals.get())
            assertEquals(setOf(1 to newToken, 2 to newToken), macroRequests.toSet())
            assertEquals(2, macroRequests.size)
            assertEquals(listOf("Candidate 2"), emitted.toList())
        } finally {
            cleanup(operation, releaseUnauthorized, releaseSibling)
            entered.close()
        }
    }

    @Test
    fun renewalFailureStopsSearchEvenWhenSiblingHasNotRejectedToken() = scenario {
        val entered = Channel<Int>(Channel.UNLIMITED)
        val rejectFirst = CompletableDeferred<Unit>()
        val releaseSibling = CompletableDeferred<Unit>()
        val requested = ConcurrentLinkedQueue<Int>()
        val callbacks = AtomicInteger()
        install(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("track.search") ->
                    respond(search(1, 2, 3, 4), headers = jsonHeaders)
                request.url.encodedPath.endsWith("token.get") ->
                    respond("", HttpStatusCode.ServiceUnavailable, jsonHeaders)
                else -> {
                    val id = request.url.parameters["track_id"]!!.toInt()
                    requested.add(id)
                    entered.send(id)
                    if (id == 1) {
                        rejectFirst.await()
                        respond(status(401), headers = jsonHeaders)
                    } else if (id == 2) {
                        releaseSibling.await()
                        respond(macro(id), headers = jsonHeaders)
                    } else {
                        respond(macro(id), headers = jsonHeaders)
                    }
                }
            }
        })
        val operation = async {
            Musixmatch.getAllLyrics("Song", "Artist") { callbacks.incrementAndGet() }
        }
        try {
            assertEquals(setOf(1, 2), setOf(entered.receive(), entered.receive()))
            rejectFirst.complete(Unit)
            releaseSibling.complete(Unit)
            assertTrue(failure { operation.await() } is MusixmatchError.TokenInvalid)
            assertEquals(setOf(1, 2), requested.toSet())
            assertEquals(1, callbacks.get())
        } finally {
            cleanup(operation, rejectFirst, releaseSibling)
            entered.close()
        }
    }

    @Test
    fun failedSharedRenewalStopsBothCandidatesWithoutRetryOrSecondRenewal() = scenario {
        val entered = Channel<Int>(Channel.UNLIMITED)
        val releaseUnauthorized = CompletableDeferred<Unit>()
        val releaseRenewal = CompletableDeferred<Unit>()
        val renewalStarted = CompletableDeferred<Unit>()
        val renewals = AtomicInteger()
        val macroRequests = AtomicInteger()
        val callbacks = AtomicInteger()
        install(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("track.search") ->
                    respond(search(1, 2, 3), headers = jsonHeaders)
                request.url.encodedPath.endsWith("token.get") -> {
                    renewals.incrementAndGet()
                    renewalStarted.complete(Unit)
                    releaseRenewal.await()
                    respond("", HttpStatusCode.ServiceUnavailable, jsonHeaders)
                }
                request.url.encodedPath.endsWith("macro.subtitles.get") -> {
                    macroRequests.incrementAndGet()
                    entered.send(request.url.parameters["track_id"]!!.toInt())
                    releaseUnauthorized.await()
                    respond(status(401), headers = jsonHeaders)
                }
                else -> error("Unexpected endpoint")
            }
        })
        val operation = async {
            Musixmatch.getAllLyrics("Song", "Artist") { callbacks.incrementAndGet() }
        }
        try {
            assertEquals(setOf(1, 2), setOf(entered.receive(), entered.receive()))
            releaseUnauthorized.complete(Unit)
            renewalStarted.await()
            assertEquals(1, renewals.get())
            releaseRenewal.complete(Unit)
            failure { operation.await() }
            assertEquals(1, renewals.get())
            assertEquals(2, macroRequests.get())
            assertEquals(0, callbacks.get())
        } finally {
            cleanup(operation, releaseUnauthorized, releaseRenewal)
            entered.close()
        }
    }

    @Test
    fun unauthorizedRetryIsTerminalDrainsActiveSiblingAndStopsQueue() = scenario {
        val entered = Channel<Int>(Channel.UNLIMITED)
        val releaseUnauthorized = CompletableDeferred<Unit>()
        val releaseSibling = CompletableDeferred<Unit>()
        val renewals = AtomicInteger()
        val requested = ConcurrentLinkedQueue<Pair<Int, String?>>()
        val callbacks = AtomicInteger()
        install(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("track.search") ->
                    respond(search(1, 2, 3), headers = jsonHeaders)
                request.url.encodedPath.endsWith("token.get") -> {
                    renewals.incrementAndGet()
                    respond(token(), headers = jsonHeaders)
                }
                request.url.encodedPath.endsWith("macro.subtitles.get") -> {
                    val id = request.url.parameters["track_id"]!!.toInt()
                    requested.add(id to request.url.parameters["usertoken"])
                    entered.send(id)
                    if (id == 1) {
                        releaseUnauthorized.await()
                        respond(status(401), headers = jsonHeaders)
                    } else {
                        releaseSibling.await()
                        respond(macro(id), headers = jsonHeaders)
                    }
                }
                else -> error("Unexpected endpoint")
            }
        })
        val operation = async {
            Musixmatch.getAllLyrics("Song", "Artist") { callbacks.incrementAndGet() }
        }
        try {
            assertEquals(setOf(1, 2), setOf(entered.receive(), entered.receive()))
            releaseUnauthorized.complete(Unit)
            assertEquals(1, entered.receive())
            releaseSibling.complete(Unit)
            assertTrue(failure { operation.await() } is MusixmatchError.TokenInvalid)
            assertEquals(1, renewals.get())
            assertEquals(
                mapOf((1 to oldToken) to 1, (1 to newToken) to 1, (2 to oldToken) to 1),
                requested.groupingBy { it }.eachCount(),
            )
            assertEquals(1, callbacks.get())
        } finally {
            cleanup(operation, releaseUnauthorized, releaseSibling)
            entered.close()
        }
    }

    @Test
    fun httpRateLimitDrainsActiveSiblingPreservesEmittedLyricsAndStopsQueue() = rateLimitScenario(payload = false)

    @Test
    fun payloadRateLimitDrainsActiveSiblingPreservesEmittedLyricsAndStopsQueue() = rateLimitScenario(payload = true)

    private fun rateLimitScenario(payload: Boolean) = scenario {
        val entered = Channel<Int>(Channel.UNLIMITED)
        val callbacks = Channel<String>(Channel.UNLIMITED)
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseRateLimit = CompletableDeferred<Unit>()
        val releaseSibling = CompletableDeferred<Unit>()
        val renewals = AtomicInteger()
        val requested = ConcurrentLinkedQueue<Int>()
        val emitted = ConcurrentLinkedQueue<String>()
        install(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("track.search") ->
                    respond(search(1, 2, 3, 4), headers = jsonHeaders)
                request.url.encodedPath.endsWith("token.get") -> {
                    renewals.incrementAndGet()
                    respond(token(), headers = jsonHeaders)
                }
                request.url.encodedPath.endsWith("macro.subtitles.get") -> {
                    val id = request.url.parameters["track_id"]!!.toInt()
                    requested.add(id)
                    entered.send(id)
                    when (id) {
                        1 -> {
                            releaseFirst.await()
                            respond(macro(id), headers = jsonHeaders)
                        }
                        2 -> {
                            releaseRateLimit.await()
                            if (payload) respond(status(429), headers = jsonHeaders)
                            else respond("", HttpStatusCode.TooManyRequests, jsonHeaders)
                        }
                        else -> {
                            releaseSibling.await()
                            respond(macro(id), headers = jsonHeaders)
                        }
                    }
                }
                else -> error("Unexpected endpoint")
            }
        })
        val operation = async {
            Musixmatch.getAllLyrics("Song", "Artist") {
                emitted.add(it)
                callbacks.trySend(it).getOrThrow()
            }
        }
        try {
            assertEquals(setOf(1, 2), setOf(entered.receive(), entered.receive()))
            releaseFirst.complete(Unit)
            assertEquals("Candidate 1", callbacks.receive())
            assertEquals(3, entered.receive())
            releaseRateLimit.complete(Unit)
            releaseSibling.complete(Unit)
            assertEquals("Candidate 3", callbacks.receive())
            assertTrue(failure { operation.await() } is MusixmatchError.RateLimited)
            assertEquals(listOf("Candidate 1", "Candidate 3"), emitted.toList())
            assertEquals(0, renewals.get())
            assertEquals(mapOf(1 to 1, 2 to 1, 3 to 1), requested.groupingBy { it }.eachCount())
        } finally {
            cleanup(operation, releaseFirst, releaseRateLimit, releaseSibling)
            entered.close()
            callbacks.close()
        }
    }

    private fun install(engine: MockEngine) {
        Musixmatch.resetForTesting(engine)
        Musixmatch.cachedToken = oldToken
    }

    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000) {
            supervisorScope(block)
        }
    }

    private suspend fun cleanup(operation: Job, vararg gates: CompletableDeferred<Unit>) {
        operation.cancel()
        gates.forEach { it.complete(Unit) }
        withContext(NonCancellable) {
            withTimeout(5_000) { operation.cancelAndJoin() }
        }
    }

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (error: Exception) {
            // A timeout is a broken barrier, not evidence of the expected API failure.
            if (error is kotlinx.coroutines.CancellationException) throw error
            return error
        }
        throw AssertionError("Expected operation to fail")
    }

    private fun status(code: Int): String =
        """{"message":{"header":{"status_code":$code},"body":{}}}"""

    private fun token(): String =
        """{"message":{"header":{"status_code":200},"body":{"user_token":"$newToken"}}}"""

    private fun search(vararg ids: Int): String {
        val tracks = ids.joinToString(",") { id ->
            """{"track":{"track_id":$id,"track_name":"Song","artist_name":"Artist","track_length":180,"has_lyrics":1,"instrumental":0}}"""
        }
        return """{"message":{"header":{"status_code":200},"body":{"track_list":[$tracks]}}}"""
    }

    private fun macro(id: Int): String =
        """
        {"message":{"header":{"status_code":200},"body":{"macro_calls":{
            "matcher.track.get":{"message":{"header":{"status_code":200},"body":{"track":{
                "track_id":$id,"track_name":"Song","artist_name":"Artist","track_length":180,
                "has_richsync":0,"has_subtitles":0,"has_lyrics":1,"instrumental":0
            }}}},
            "track.lyrics.get":{"message":{"header":{"status_code":200},"body":{"lyrics":{
                "lyrics_id":$id,"lyrics_body":"Candidate $id","restricted":0,"instrumental":0
            }}}}
        }}}}
        """.trimIndent()
}
