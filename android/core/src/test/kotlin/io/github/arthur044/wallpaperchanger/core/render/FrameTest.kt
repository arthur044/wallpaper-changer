package io.github.arthur044.wallpaperchanger.core.render

import io.github.arthur044.wallpaperchanger.core.config.ArtFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class FrameTest {
    private val art = PixelRect(100, 300, 900, 1100) // 800 px side

    @Test
    fun `no frame leaves the art as laid out`() {
        assertEquals(art, framedArt(art, ArtFrame.NONE))
        assertTrue(frameRims(ArtFrame.NONE).isEmpty())
    }

    @Test
    fun `the outer rim fills exactly the art's original box`() {
        for (frame in listOf(ArtFrame.SINGLE, ArtFrame.DOUBLE)) {
            val inner = framedArt(art, frame)
            val gap = (inner.width * frameRims(frame).first().gapOfArt).roundToInt()
            assertTrue(abs((inner.left - gap) - art.left) <= 1, "$frame left")
            assertTrue(abs((inner.right + gap) - art.right) <= 1, "$frame right")
            assertEquals(inner.width, inner.height)
            assertTrue(abs(art.centerX - inner.centerX) <= 1)
            assertTrue(abs(art.centerY - inner.centerY) <= 1)
        }
    }

    @Test
    fun `a single frame leaves the art bigger than a double one`() {
        assertTrue(framedArt(art, ArtFrame.SINGLE).width > framedArt(art, ArtFrame.DOUBLE).width)
    }

    @Test
    fun `rims match the desktop, outermost first`() {
        assertEquals(listOf(FrameRim(0.05f, 28, 110)), frameRims(ArtFrame.SINGLE))
        assertEquals(listOf(FrameRim(0.075f, 24, 90), FrameRim(0.035f, 28, 110)), frameRims(ArtFrame.DOUBLE))
    }
}
