/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import moe.rukamori.archivetune.musixmatch.models.Track
import moe.rukamori.archivetune.musixmatch.models.TrackContainer
import moe.rukamori.archivetune.musixmatch.models.TrackSearchBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MusixmatchSearchSelectionTest {

    @Test
    fun `isSearchCandidate filters by duration and instrumental`() {
        val normal = Track(trackId = 1, trackName = "Song", artistName = "Artist", trackLength = 200, instrumental = 0)
        val instrumental = Track(trackId = 2, trackName = "Beat", artistName = "Producer", trackLength = 200, instrumental = 1)
        val durationMismatch = Track(trackId = 3, trackName = "Song", artistName = "Artist", trackLength = 250, instrumental = 0)

        assertTrue(isSearchCandidate(normal, durationSeconds = 205)) // within tolerance 8
        assertFalse(isSearchCandidate(instrumental, durationSeconds = 200)) // instrumental rejected
        assertFalse(isSearchCandidate(durationMismatch, durationSeconds = 200)) // 50s difference > 8s
    }

    @Test
    fun `versionDemerit assigns 1 when version qualifier does not match input`() {
        val originalTrack = Track(trackId = 1, trackName = "Song", artistName = "Artist")
        val remixTrack = Track(trackId = 2, trackName = "Song (Remix)", artistName = "Artist")
        val liveTrack = Track(trackId = 3, trackName = "Song - Live", artistName = "Artist")

        // Input is original "Song"
        val inputMarkers = versionMarkers("Song")
        assertEquals(0, versionDemerit(originalTrack, inputMarkers))
        assertEquals(1, versionDemerit(remixTrack, inputMarkers))
        assertEquals(1, versionDemerit(liveTrack, inputMarkers))

        // Input is remix "Song (Remix)"
        val remixMarkers = versionMarkers("Song (Remix)")
        assertEquals(0, versionDemerit(remixTrack, remixMarkers))
        assertEquals(1, versionDemerit(originalTrack, remixMarkers))
    }

    @Test
    fun `selectSearchCandidates sorts by demerit then track rating`() {
        val searchBody = TrackSearchBody(
            trackList = listOf(
                TrackContainer(Track(trackId = 1, trackName = "Song (Remix)", artistName = "Artist", trackLength = 200, trackRating = 99)),
                TrackContainer(Track(trackId = 2, trackName = "Song", artistName = "Artist", trackLength = 200, trackRating = 50)),
                TrackContainer(Track(trackId = 3, trackName = "Song", artistName = "Artist", trackLength = 200, trackRating = 80)),
            ),
        )

        val candidates = selectSearchCandidates(
            body = searchBody,
            inputTitle = "Song",
            durationSeconds = 200,
            limit = 2,
        )

        assertEquals(2, candidates.size)
        // Original with highest rating first (trackId 3, rating 80)
        assertEquals(3L, candidates[0].track.trackId)
        assertEquals(0, candidates[0].demerit)

        // Original with second highest rating (trackId 2, rating 50)
        assertEquals(2L, candidates[1].track.trackId)
        assertEquals(0, candidates[1].demerit)
    }

    @Test
    fun `validateMatch passes when title and artist match within duration tolerance`() {
        val track = Track(trackId = 1, trackName = "Shape of You", artistName = "Ed Sheeran", trackLength = 233)
        validateMatch(track, inputTitle = "Shape of You", inputArtist = "Ed Sheeran", inputDuration = 235)
    }

    @Test
    fun `validateMatch skips title and artist check when not provided by caller`() {
        val track = Track(trackId = 1, trackName = "Any Name", artistName = "Any Artist", trackLength = 200)
        // Only duration provided (as in search fan-out)
        validateMatch(track, inputTitle = null, inputArtist = null, inputDuration = 202)
    }

    @Test
    fun `validateMatch throws Mismatch on duration mismatch`() {
        val track = Track(trackId = 1, trackName = "Song", artistName = "Artist", trackLength = 200)
        try {
            validateMatch(track, inputTitle = "Song", inputArtist = "Artist", inputDuration = 220)
            fail("Expected Mismatch")
        } catch (e: MusixmatchError.Mismatch) {
            assertTrue(e.message!!.contains("differs from input"))
        }
    }
}
