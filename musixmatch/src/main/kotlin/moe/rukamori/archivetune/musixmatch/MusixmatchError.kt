/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

sealed class MusixmatchError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class TokenInvalid(detail: String, cause: Throwable? = null) : MusixmatchError("Token invalid: $detail", cause)
    class RateLimited(detail: String, cause: Throwable? = null) : MusixmatchError("Rate limited: $detail", cause)
    class NoMatch(detail: String, cause: Throwable? = null) : MusixmatchError("No match: $detail", cause)
    class Restricted(cause: Throwable? = null) : MusixmatchError("Lyrics restricted", cause)
    class Instrumental(message: String = "Track is instrumental", cause: Throwable? = null) : MusixmatchError(message, cause)
    class ServerError(code: Int, cause: Throwable? = null) : MusixmatchError("Server error: $code", cause)
    class MalformedResponse(detail: String, cause: Throwable? = null) : MusixmatchError("Malformed response: $detail", cause)
    class Mismatch(detail: String, cause: Throwable? = null) : MusixmatchError("Match rejected: $detail", cause)
}
