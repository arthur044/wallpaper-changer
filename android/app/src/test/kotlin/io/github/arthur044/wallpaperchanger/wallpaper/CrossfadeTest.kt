package io.github.arthur044.wallpaperchanger.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeTest {
    @Test
    fun startsHiddenAndEndsOpaque() {
        assertEquals(0, crossfadeAlpha(0, 300))
        assertEquals(255, crossfadeAlpha(300, 300))
        assertEquals(255, crossfadeAlpha(10_000, 300))
    }

    @Test
    fun neverGoesBackwards() {
        val alphas = (0L..300L step 10).map { crossfadeAlpha(it, 300) }
        assertTrue(alphas.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun isHalfWayAtTheMiddle() {
        assertEquals(128, crossfadeAlpha(150, 300))
    }

    @Test
    fun oddInputsDoNotBreakIt() {
        assertEquals(0, crossfadeAlpha(-5, 300))
        assertEquals(255, crossfadeAlpha(0, 0))
    }
}
