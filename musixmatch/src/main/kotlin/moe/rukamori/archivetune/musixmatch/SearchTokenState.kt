/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal suspend fun <T> searchRequest(block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: MusixmatchError) {
        throw e
    } catch (e: Exception) {
        throw MusixmatchError.MalformedResponse("Search request failed")
    }

internal class SearchTokenState(
    initialToken: String,
    private val renewToken: suspend (String) -> String,
) {
    @Volatile
    private var token = initialToken

    @Volatile
    private var terminalFailure: MusixmatchError? = null

    @Volatile
    private var renewalFailed = false

    val isRenewalFailed: Boolean get() = renewalFailed

    private val renewalMutex = Mutex()
    private var renewalUsed = false

    suspend fun <T> execute(block: suspend (String) -> T): T {
        terminalFailure?.let { throw it }
        val previous = token
        try {
            return searchRequest { block(previous) }
        } catch (e: MusixmatchError.RateLimited) {
            terminalFailure = e
            throw e
        } catch (e: MusixmatchError.TokenInvalid) {
            val retryToken = renewalMutex.withLock {
                terminalFailure?.let { throw it }
                if (token != previous) {
                    token
                } else {
                    if (renewalUsed) {
                        throw e
                    }
                    renewalUsed = true
                    try {
                        token = searchRequest { renewToken(previous) }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        renewalFailed = true
                        if (failure is MusixmatchError.RateLimited) {
                            terminalFailure = failure
                            throw failure
                        }
                        throw MusixmatchError.TokenInvalid("Search token renewal failed", failure)
                    }
                    token
                }
            }
            terminalFailure?.let { throw it }
            return try {
                searchRequest { block(retryToken) }
            } catch (failure: MusixmatchError.TokenInvalid) {
                terminalFailure = failure
                throw failure
            } catch (failure: MusixmatchError.RateLimited) {
                terminalFailure = failure
                throw failure
            }
        }
    }
}
