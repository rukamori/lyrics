/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SearchTokenStateTest {

    @Test
    fun `execute returns result when token is valid`() = runBlocking {
        val state = SearchTokenState(
            initialToken = "token-1",
            renewToken = { "token-renewed" },
        )

        val result = state.execute { token ->
            assertEquals("token-1", token)
            "success-payload"
        }

        assertEquals("success-payload", result)
        assertEquals("token-1", state.current())
    }

    @Test
    fun `execute renews token once on TokenInvalid and retries`() = runBlocking {
        val renewalCount = AtomicInteger(0)
        val state = SearchTokenState(
            initialToken = "token-1",
            renewToken = {
                renewalCount.incrementAndGet()
                "token-2"
            },
        )

        var attempts = 0
        val result = state.execute { token ->
            attempts++
            if (token == "token-1") {
                throw MusixmatchError.TokenInvalid("expired")
            }
            "result-$token"
        }

        assertEquals("result-token-2", result)
        assertEquals(2, attempts)
        assertEquals(1, renewalCount.get())
        assertEquals("token-2", state.current())
    }

    @Test
    fun `concurrent requests share single renewal when token is invalid`() = runBlocking {
        val renewalCount = AtomicInteger(0)
        val state = SearchTokenState(
            initialToken = "token-stale",
            renewToken = {
                renewalCount.incrementAndGet()
                "token-fresh"
            },
        )

        val deferredResults = (1..10).map { i ->
            async {
                state.execute { token ->
                    if (token == "token-stale") {
                        throw MusixmatchError.TokenInvalid("expired")
                    }
                    "res-$i-$token"
                }
            }
        }

        val results = deferredResults.awaitAll()
        assertEquals(10, results.size)
        assertTrue(results.all { it.endsWith("token-fresh") })
        assertEquals(1, renewalCount.get())
        assertEquals("token-fresh", state.current())
    }

    @Test
    fun `execute throws TokenInvalid when renewal quota is exhausted`() = runBlocking {
        val state = SearchTokenState(
            initialToken = "token-1",
            renewToken = { "token-2" },
        )

        try {
            state.execute { token ->
                // Always fail with TokenInvalid
                throw MusixmatchError.TokenInvalid("fail-$token")
            }
            fail("Expected TokenInvalid to be thrown")
        } catch (e: MusixmatchError.TokenInvalid) {
            assertTrue(e.message!!.contains("fail-token-2"))
        }

        assertNotNull(state.terminalFailure)
    }

    @Test
    fun `RateLimited poisons state and blocks subsequent executions`() = runBlocking {
        var callCount = 0
        val state = SearchTokenState(
            initialToken = "token-1",
            renewToken = { "token-2" },
        )

        try {
            state.execute {
                callCount++
                throw MusixmatchError.RateLimited("too many requests")
            }
            fail("Expected RateLimited")
        } catch (e: MusixmatchError.RateLimited) {
            assertEquals("Rate limited: too many requests", e.message)
        }

        assertEquals(1, callCount)
        assertNotNull(state.terminalFailure)

        // Subsequent call should immediately throw without invoking block
        try {
            state.execute {
                callCount++
                "should not be called"
            }
            fail("Expected RateLimited from poisoned state")
        } catch (e: MusixmatchError.RateLimited) {
            assertEquals("Rate limited: too many requests", e.message)
        }

        assertEquals(1, callCount)
    }

    @Test
    fun `RateLimited during renewal poisons state`() = runBlocking {
        val state = SearchTokenState(
            initialToken = "token-1",
            renewToken = {
                throw MusixmatchError.RateLimited("blocked at auth")
            },
        )

        try {
            state.execute {
                throw MusixmatchError.TokenInvalid("expired")
            }
            fail("Expected RateLimited")
        } catch (e: MusixmatchError.RateLimited) {
            assertEquals("Rate limited: blocked at auth", e.message)
        }

        assertTrue(state.terminalFailure is MusixmatchError.RateLimited)
    }
}
