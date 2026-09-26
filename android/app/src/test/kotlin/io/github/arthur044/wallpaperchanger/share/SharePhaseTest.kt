package io.github.arthur044.wallpaperchanger.share

import io.github.arthur044.wallpaperchanger.core.lyrics.Lyrics
import io.github.arthur044.wallpaperchanger.core.lyrics.LyricsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The share screen follows the early lookup, but only for the track it was opened for.
class SharePhaseTest {
    private val words = Lyrics.Text(listOf("Placeholder line 1"))

    @Test
    fun eachAnswerHasItsScreen() {
        assertEquals(SharePhase.LOADING, phaseFor(LyricsState.Loading("t1"), "t1"))
        assertEquals(SharePhase.CHOOSING, phaseFor(LyricsState.Ready("t1", words), "t1"))
        assertEquals(SharePhase.NO_LYRICS, phaseFor(LyricsState.Ready("t1", Lyrics.NotFound), "t1"))
        assertEquals(SharePhase.INSTRUMENTAL, phaseFor(LyricsState.Ready("t1", Lyrics.Instrumental), "t1"))
        assertEquals(SharePhase.NO_NETWORK, phaseFor(LyricsState.Unavailable("t1"), "t1"))
    }

    @Test
    fun anotherTracksAnswerIsNotThisScreens() {
        assertNull(phaseFor(LyricsState.Ready("t2", words), "t1"))
        assertNull(phaseFor(LyricsState.Loading("t2"), "t1"))
        assertNull(phaseFor(LyricsState.Unavailable("t2"), "t1"))
        assertNull(phaseFor(LyricsState.None, "t1"))
    }
}
