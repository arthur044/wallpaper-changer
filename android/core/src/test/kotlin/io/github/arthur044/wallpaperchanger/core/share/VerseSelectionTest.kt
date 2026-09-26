package io.github.arthur044.wallpaperchanger.core.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerseSelectionTest {
    //                  0     1     2    3     4     5    6
    private val lines = listOf("a", "b", "c", "", "d", "e", "")
    private val always = { _: VerseSelection -> true }

    private fun VerseSelection?.tapAll(vararg taps: Int, fits: (VerseSelection) -> Boolean = always): VerseSelection? =
        taps.fold(this) { selection, i -> selection.tap(i, lines, fits).selection }

    @Test
    fun `the first tap selects that line`() {
        assertEquals(VerseSelection(1, 1), null.tapAll(1))
    }

    @Test
    fun `the next line on either side grows the selection`() {
        assertEquals(VerseSelection(0, 2), null.tapAll(1, 2, 0))
    }

    @Test
    fun `growing across a stanza break takes the blank line in`() {
        assertEquals(VerseSelection(2, 4), null.tapAll(2, 4))
        assertEquals(listOf("c", "", "d"), VerseSelection(2, 4).of(lines))
    }

    @Test
    fun `a blank line can't be tapped`() {
        assertNull(null.tapAll(3))
        assertEquals(VerseSelection(1, 2), null.tapAll(1, 2, 3))
    }

    @Test
    fun `tapping an end shrinks the selection, past a stanza break`() {
        assertEquals(VerseSelection(1, 2), null.tapAll(0, 1, 2, 0))
        assertEquals(VerseSelection(1, 2), null.tapAll(1, 2, 4, 4))
        assertEquals(VerseSelection(4, 5), null.tapAll(2, 4, 5, 2))
    }

    @Test
    fun `tapping the only selected line clears it`() {
        assertNull(null.tapAll(2, 2))
    }

    @Test
    fun `a line away from the selection starts a new one`() {
        assertEquals(VerseSelection(5, 5), null.tapAll(0, 1, 5))
        assertEquals(VerseSelection(1, 1), null.tapAll(0, 1, 2, 1))
    }

    @Test
    fun `a selection that doesn't fit is refused and the old one kept`() {
        val atMostTwo = { s: VerseSelection -> s.last - s.first < 2 }
        val result = VerseSelection(0, 1).tap(2, lines, atMostTwo)

        assertTrue(result.refused)
        assertEquals(VerseSelection(0, 1), result.selection)
    }

    @Test
    fun `a single line that doesn't fit is refused`() {
        val result = null.tap(0, lines) { false }
        assertTrue(result.refused)
        assertNull(result.selection)
    }

    @Test
    fun `shrinking is never refused`() {
        val result = VerseSelection(0, 2).tap(2, lines) { false }
        assertFalse(result.refused)
        assertEquals(VerseSelection(0, 1), result.selection)
    }
}
