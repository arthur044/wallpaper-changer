package io.github.arthur044.wallpaperchanger.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Ported from tests/test_poller_state_machine.py.
class DecideTest {
    private fun playing(trackId: String? = "t1", albumId: String? = "a1", isPlaying: Boolean = true) =
        NowPlaying(
            isPlaying = isPlaying,
            trackId = trackId,
            albumId = albumId,
            artUrl = "http://x/y.jpg",
            trackName = "Some Track",
            artistName = "Some Artist",
        )

    @Test
    fun `idle when nothing is playing`() {
        assertEquals(PollDecision.IDLE, decide(null, lastRenderedTrackId = null))
    }

    @Test
    fun `idle when paused`() {
        assertEquals(PollDecision.IDLE, decide(playing(isPlaying = false), lastRenderedTrackId = null))
    }

    @Test
    fun `idle when the album is unknown`() {
        // Without an album id there is no base art to render or cache.
        assertEquals(PollDecision.IDLE, decide(playing(albumId = null), lastRenderedTrackId = null))
    }

    @Test
    fun `render on a new album`() {
        assertEquals(PollDecision.RENDER, decide(playing(trackId = "t2", albumId = "a2"), lastRenderedTrackId = "t1"))
    }

    @Test
    fun `render on a new track within the same album`() {
        // Track text must update even when the album (and its cached base art) is unchanged.
        assertEquals(PollDecision.RENDER, decide(playing(trackId = "t2", albumId = "a1"), lastRenderedTrackId = "t1"))
    }

    @Test
    fun `noop when the same track is still playing`() {
        assertEquals(PollDecision.NOOP, decide(playing(trackId = "t1", albumId = "a1"), lastRenderedTrackId = "t1"))
    }

    @Test
    fun `render on the first ever track`() {
        assertEquals(PollDecision.RENDER, decide(playing(trackId = "t1", albumId = "a1"), lastRenderedTrackId = null))
    }
}
