package io.github.arthur044.wallpaperchanger.core.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanvasSpecTest {
    @Test
    fun `a phone gets a screen-sized canvas minus the system bars`() {
        val spec = canvasSpec(ScreenMetrics(1080, 2400, density = 2.625f, smallestWidthDp = 411, insets = Insets(0, 63, 0, 126)))

        assertEquals(1080, spec.canvasWidth)
        assertEquals(2400, spec.canvasHeight)
        assertEquals(PixelRect(0, 63, 1080, 2274), spec.safeArea)
        assertEquals(2.625f, spec.density)
    }

    @Test
    fun `a phone measured in landscape still gets a portrait wallpaper`() {
        // The app may be asked while rotated; phone wallpapers stay portrait.
        // In landscape the nav bar sits on a side, so it becomes the bottom inset.
        val spec = canvasSpec(ScreenMetrics(2400, 1080, density = 2.625f, smallestWidthDp = 411, insets = Insets(0, 63, 126, 0)))

        assertEquals(1080, spec.canvasWidth)
        assertEquals(2400, spec.canvasHeight)
        assertEquals(PixelRect(0, 63, 1080, 2274), spec.safeArea)
    }

    @Test
    fun `a tablet gets a square canvas with content in the rotation-safe center`() {
        val spec = canvasSpec(ScreenMetrics(2560, 1600, density = 2f, smallestWidthDp = 800, insets = Insets(0, 48, 0, 96)))

        assertEquals(2560, spec.canvasWidth)
        assertEquals(2560, spec.canvasHeight)
        // Centered 1600 square (visible in both orientations), shrunk by the
        // largest system bar so no bar can cover it in either orientation.
        assertEquals(PixelRect(480 + 96, 480 + 96, 2080 - 96, 2080 - 96), spec.safeArea)
    }

    @Test
    fun `the safe area always lies inside the canvas`() {
        val shapes = listOf(
            ScreenMetrics(1080, 2400, 2.625f, 411, Insets(0, 63, 0, 126)),
            ScreenMetrics(2208, 1840, 2.625f, 673, Insets(0, 70, 0, 70)),
            ScreenMetrics(1600, 2560, 2f, 800, Insets(0, 48, 0, 96)),
        )
        shapes.forEach { m ->
            val spec = canvasSpec(m)
            val s = spec.safeArea
            assertTrue(s.left >= 0 && s.top >= 0 && s.right <= spec.canvasWidth && s.bottom <= spec.canvasHeight, "$m -> $s")
            assertTrue(s.width > 0 && s.height > 0)
        }
    }
}
