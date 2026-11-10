/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages token lifecycle for a single high-level operation (playback or search).
 *
 * Enforces:
 * - At most 1 token renewal per operation.
 * - Thread-safe token access and concurrent renewal sharing via Mutex.
 * - Terminal failure poisoning (e.g., RateLimited halts subsequent attempts without network calls).
 */
internal class SearchTokenState(
    initialToken: String,
    private val renewToken: suspend () -> String,
) {
    @Volatile
    private var token: String = initialToken

    @Volatile
    var terminalFailure: MusixmatchError? = null
        private set

    private var renewalUsed: Boolean = false
    private val renewalMutex = Mutex()

    fun current(): String = token

    suspend fun <T> execute(block: suspend (token: String) -> T): T {
        terminalFailure?.let { throw it }

        val activeToken = token
        return try {
            block(activeToken)
        } catch (e: MusixmatchError.TokenInvalid) {
            handleTokenInvalid(activeToken, e, block)
        } catch (e: MusixmatchError.RateLimited) {
            terminalFailure = e
            throw e
        }
    }

    private suspend fun <T> handleTokenInvalid(
        failedToken: String,
        originalError: MusixmatchError.TokenInvalid,
        block: suspend (token: String) -> T,
    ): T {
        renewalMutex.withLock {
            terminalFailure?.let { throw it }

            // If another concurrent request already renewed the token, reuse it
            if (token != failedToken) {
                return block(token)
            }

            if (renewalUsed) {
                terminalFailure = originalError
                throw originalError
            }

            val newToken = try {
                renewToken()
            } catch (e: MusixmatchError.RateLimited) {
                terminalFailure = e
                throw e
            } catch (e: Exception) {
                val error = MusixmatchError.TokenInvalid("Failed to renew token", e)
                terminalFailure = error
                throw error
            }

            token = newToken
            renewalUsed = true
        }

        return try {
            block(token)
        } catch (e: MusixmatchError.TokenInvalid) {
            terminalFailure = e
            throw e
        } catch (e: MusixmatchError.RateLimited) {
            terminalFailure = e
            throw e
        }
    }
}
