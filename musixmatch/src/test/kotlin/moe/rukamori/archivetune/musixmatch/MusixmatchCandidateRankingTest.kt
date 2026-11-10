/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import moe.rukamori.archivetune.musixmatch.models.SearchBody
import moe.rukamori.archivetune.musixmatch.models.SearchTrackItem
import moe.rukamori.archivetune.musixmatch.models.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusixmatchCandidateRankingTest {
    private fun selected(title: String, vararg tracks: Track, limit: Int = 4): List<Long> =
        selectSearchCandidates(SearchBody(tracks.map { SearchTrackItem(it) }), title, 100, limit).map { it.track.trackId }

    private fun track(id: Long, title: String = "Song", rating: Int = 0, album: String = "", length: Int = 100) =
        Json { ignoreUnknownKeys = true }.decodeFromString<Track>(
            """{"track_id":$id,"track_name":${Json.encodeToString(title)},"artist_name":"Artist","track_length":$length,"track_rating":$rating,"album_name":${Json.encodeToString(album)}}"""
        )

    @Test
    fun canonicalBeatsHigherRatedVersionBeforeCap() {
        assertEquals(listOf(2L, 3L), selected("Song", track(1, "Song (Live)", 100), track(2, rating = 80), track(3, rating = 20), limit = 2))
    }

    @Test
    fun ratingBreaksTiesWithinSameVersionTier() {
        assertEquals(listOf(3L, 1L, 2L), selected("Song", track(1, rating = 20), track(2, rating = 10), track(3, rating = 90)))
    }

    @Test
    fun equalKeysKeepResponseOrderRatherThanTrackIdOrder() {
        assertEquals(listOf(9L, 2L, 7L), selected("Song", track(9), track(2), track(7)))
    }

    @Test
    fun everyQualifierMarkerGetsDemerit() {
        for (marker in listOf("remix", "live", "cover", "instrumental", "acoustic", "arrangement", "tribute", "karaoke")) {
            assertEquals(listOf(2L, 1L), selected("Song", track(1, "Song ($marker)", 100), track(2, rating = 10)))
        }
    }

    @Test
    fun bracketsAndBothDashSeparatorsAreQualifiers() {
        for (name in listOf("Song [Live]", "Song - Live", "Song – Live")) {
            assertEquals(listOf(2L, 1L), selected("Song", track(1, name, 100), track(2, rating = 10)))
        }
    }

    @Test
    fun explicitlyRequestedVersionIsNotPenalized() {
        assertEquals(listOf(1L, 2L), selected("Song (Slow Jam Remix)", track(1, "Song (Bedroom Remix)", 100), track(2, rating = 10)))
    }

    @Test
    fun requestedVersionOutranksHigherRatedCanonical() {
        assertEquals(listOf(1L, 2L), selected("Dynamite (Slow Jam Remix)", track(2, "Dynamite", 90), track(1, "Dynamite (Slow Jam Remix)", 44)))
    }

    @Test
    fun requestedRemixDoesNotExcuseUnrequestedLiveMarker() {
        assertEquals(listOf(2L, 1L), selected("Song (Remix)", track(1, "Song (Live Remix)", 100), track(2, "Song (Remix)", 10)))
    }

    @Test
    fun normalTitlesAndPartialMarkerWordsAreNotQualifiers() {
        for (name in listOf("Live Forever", "Alive", "Song (Olive Sessions)", "Song (Discovery Edition)")) {
            assertEquals(listOf(1L, 2L), selected("Song", track(1, name, 100), track(2, rating = 10)))
        }
    }

    @Test
    fun albumCoverMarkerIsPenalizedButOtherAlbumMarkersAreIgnored() {
        assertEquals(listOf(2L, 1L), selected("Song", track(1, rating = 100, album = "Official Covers, Vol.1"), track(2, rating = 10)))
        assertEquals(listOf(1L, 2L), selected("Song", track(1, rating = 100, album = "Live at Wembley"), track(2, rating = 10)))
        assertEquals(listOf(1L, 2L), selected("Song", track(1, rating = 100, album = "Discovery"), track(2, rating = 10)))
    }

    @Test
    fun gateAndDedupStillPrecedeRatingAndLimit() {
        assertEquals(listOf(2L, 3L), selected("Song", track(1, rating = 100, length = 109), track(2, rating = 10), track(2, rating = 100), track(3, rating = 5), limit = 2))
    }

    @Test
    fun candidatesRetainDemerit() {
        val body = SearchBody(
            listOf(
                SearchTrackItem(track(1, "Song (Live)", 100)),
                SearchTrackItem(track(2, rating = 80)),
            ),
        )
        val candidates = selectSearchCandidates(body, "Song", 100, limit = 2)
        assertEquals(2, candidates.size)
        assertEquals(2L, candidates[0].track.trackId)
        assertEquals(0, candidates[0].demerit)
        assertEquals(1L, candidates[1].track.trackId)
        assertEquals(1, candidates[1].demerit)
    }

    @Test
    fun versionMatchBeatsHigherQualityMismatchedVersion() {
        // Demerit 0 plain (quality 0) beats Demerit 1 richsync (quality 2)
        val studio = CandidateOutcome.Success(SearchCandidate(track(1, "Dynamite", rating = 95), 0), 1, ResolvedLyrics("plain", 0))
        val live = CandidateOutcome.Success(SearchCandidate(track(2, "Dynamite - Live", rating = 88), 1), 2, ResolvedLyrics("richsync", 2))
        val sorted = listOf(live, studio).sortedWith(FALLBACK_SUCCESS_COMPARATOR)
        assertEquals(1L, sorted.first().candidate.track.trackId)
    }

    @Test
    fun higherQualityWinsWhenDemeritIsEqual() {
        val plain = CandidateOutcome.Success(SearchCandidate(track(1, "Song", rating = 90), 0), 1, ResolvedLyrics("plain", 0))
        val ttml = CandidateOutcome.Success(SearchCandidate(track(2, "Song", rating = 80), 0), 2, ResolvedLyrics("ttml", 2))
        val sorted = listOf(plain, ttml).sortedWith(FALLBACK_SUCCESS_COMPARATOR)
        assertEquals(2L, sorted.first().candidate.track.trackId)
    }

    @Test
    fun ratingBreaksTieWhenDemeritAndQualityAreEqual() {
        val lowerRated = CandidateOutcome.Success(SearchCandidate(track(1, "Song", rating = 70), 0), 1, ResolvedLyrics("ttml", 2))
        val higherRated = CandidateOutcome.Success(SearchCandidate(track(2, "Song", rating = 95), 0), 2, ResolvedLyrics("ttml", 2))
        val sorted = listOf(lowerRated, higherRated).sortedWith(FALLBACK_SUCCESS_COMPARATOR)
        assertEquals(2L, sorted.first().candidate.track.trackId)
    }

    @Test
    fun earlierRankBreaksTieWhenDemeritQualityAndRatingAreEqual() {
        val rank1 = CandidateOutcome.Success(SearchCandidate(track(1, "Song", rating = 80), 0), 1, ResolvedLyrics("ttml", 2))
        val rank2 = CandidateOutcome.Success(SearchCandidate(track(2, "Song", rating = 80), 0), 2, ResolvedLyrics("ttml", 2))
        val sorted = listOf(rank2, rank1).sortedWith(FALLBACK_SUCCESS_COMPARATOR)
        assertEquals(1L, sorted.first().candidate.track.trackId)
        assertEquals(1, sorted.first().rank)
    }

    @Test
    fun rank1Quality1CannotEarlyStopIfSameDemeritRemains() {
        val rank1 = CandidateOutcome.Success(SearchCandidate(track(1, "Song", rating = 80), 0), 1, ResolvedLyrics("lrc", 1))
        val rank2 = SearchCandidate(track(2, "Song", rating = 80), 0)
        val maxPossibleRank2 = CandidateOutcome.Success(rank2, 2, ResolvedLyrics("ttml", 2))
        // rank 1 with quality 1 is worse than maxPossibleRank2 with quality 2, so cannot early stop
        assertTrue(FALLBACK_SUCCESS_COMPARATOR.compare(rank1, maxPossibleRank2) > 0)
    }
}
