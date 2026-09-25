package io.github.arthur044.wallpaperchanger.core.lyrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// spotifast's src/lyrics.rs tests, plus the titles this app actually meets.
class LyricsTextTest {

    @Test
    fun `a featuring is cut where it starts whatever lower casing costs`() {
        // The cut is found in a lower-cased copy and applied to the original.
        // Turkish 'İ' lower-cases to two chars ('i' + combining dot), so every
        // offset past it points one char too far and the letters between stay.
        assertEquals("İİ", cleanTitle("İİ feat. Someone"))
        assertEquals("İzel", cleanArtist("İzel feat. Someone"))
        // Capital sharp s: shorter in UTF-8 (the bug spotifast hit), same in UTF-16.
        assertEquals("ẞ café", cleanTitle("ẞ café feat. Someone"))
        // A character outside the BMP (two chars) before the marker.
        assertEquals("🎵 Song", cleanTitle("🎵 Song ft. Someone"))

        assertEquals("Song", cleanTitle("Song feat. Someone"))
        assertEquals("Song", cleanTitle("Song ft. Someone"))
        assertEquals("Artist", cleanArtist("Artist featuring Other"))
    }

    @Test
    fun `titles lose what a database leaves out`() {
        assertEquals("Song", cleanTitle("Song (Remastered 2011)"))
        assertEquals("Song", cleanTitle("Song - Live at Wembley"))
        assertEquals("Song", cleanTitle("Song - 2009 Remaster"))
        assertEquals("Song", cleanTitle("Song - 2011 Version"))
        assertEquals("Song", cleanTitle("Song (feat. Someone)"))
        assertEquals("Song", cleanTitle("Song [Radio Edit]"))
        assertEquals("Song (Part One)", cleanTitle("Song (Part One)"))
        assertEquals("Hyphen - Ated", cleanTitle("Hyphen - Ated"))
        assertEquals("(Remastered)", cleanTitle("(Remastered)"), "never empty")
        assertEquals("Feature", cleanTitle("Feat​ure"))
        assertEquals("Left Behind", cleanTitle("Left Behind"), "a 'ft' inside a word is no marker")
    }

    @Test
    fun `a title with quotes and a colon is kept whole`() {
        val title = "Metropolis - Part I: \"The Miracle and the Sleeper\""
        assertEquals(title, cleanTitle(title))
    }

    @Test
    fun `artists keep their first name`() {
        assertEquals("TOOL", cleanArtist("TOOL;Tool"))
        assertEquals("Artist", cleanArtist("Artist feat. Guest"))
        assertEquals("Artist", cleanArtist("Artist (feat. Guest)"))
        assertEquals("Beyoncé", cleanArtist("Beyoncé"))
    }

    @Test
    fun `matching is loose about case, accents and punctuation`() {
        assertTrue(looseMatch("Beyoncé", "beyonce"))
        assertTrue(looseMatch("Rock & Roll", "rock and roll"))
        assertTrue(looseMatch("Don't Stop", "dont stop"))
        assertTrue(looseMatch("Song (Live)", "Song"))
        assertFalse(looseMatch("Something", "Else"))
        assertFalse(looseMatch("", "Else"))
        assertFalse(looseMatch("!!!", "Else"), "punctuation alone normalizes to nothing")
    }

    @Test
    fun `LRCLIB's spellings of Metropolis all match the Spotify title`() {
        val spotify = cleanTitle("Metropolis - Part I: \"The Miracle and the Sleeper\"")
        listOf(
            "Metropolis, Part I: The Miracle and the Sleeper",
            "Metropolis—Part I “The Miracle and the Sleeper”",
            "Metropolis - Part I (The Miracle And The Sleeper)",
        ).forEach { assertTrue(looseMatch(it, spotify), it) }
    }

    @Test
    fun `LRC lines lose their stamps and come in time order`() {
        val text = lrcText("[ar:Someone]\n[00:12.50]First\n[00:05]Early\n[01:00.1][02:00.123]Twice\n\nNo stamp\n")
        assertEquals(listOf("Early", "First", "Twice", "Twice"), text)
    }

    @Test
    fun `tidy keeps one blank line between stanzas and none at the ends`() {
        assertEquals(
            listOf("a", "", "b", "c"),
            tidy(listOf("", "a  ", "", "  ", "", "b", "c", "", "")),
        )
    }
}
