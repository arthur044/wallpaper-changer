package io.github.arthur044.wallpaperchanger.core.render

import io.github.arthur044.wallpaperchanger.core.config.BackgroundStyle
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.config.TextCard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class AccentsTest {
    private fun distance(a: Rgb, b: Rgb): Double {
        val dr = (a.r - b.r).toDouble()
        val dg = (a.g - b.g).toDouble()
        val db = (a.b - b.b).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    @Test
    fun `accent palette is only needed by the effects that use it`() {
        assertFalse(needsAccentPalette(Settings()))
        // The card is drawn from the base's pixels, not from the art's colors.
        assertFalse(needsAccentPalette(Settings(textCard = TextCard.GLASS)))
        assertTrue(needsAccentPalette(Settings(artGlow = true)))
        assertTrue(needsAccentPalette(Settings(backgroundStyle = BackgroundStyle.MESH)))
    }

    @Test
    fun `glow prefers the vivid color over the common gray`() {
        val palette = listOf(Rgb(40, 40, 40), Rgb(120, 120, 120), Rgb(210, 30, 60), Rgb(90, 80, 70))
        assertEquals(Rgb(210, 30, 60), pickGlowColor(palette, background = Rgb(40, 40, 40)))
    }

    @Test
    fun `glow keeps the first of equally vivid colors, like the desktop`() {
        val palette = listOf(Rgb(30, 200, 90), Rgb(200, 30, 90))
        assertEquals(Rgb(30, 200, 90), pickGlowColor(palette, background = Rgb(10, 10, 10)))
    }

    @Test
    fun `glow lifted off a dark fill stands out from it`() {
        val background = Rgb(12, 12, 14)
        val glow = pickGlowColor(listOf(Rgb(12, 12, 14), Rgb(20, 18, 22)), background)
        assertTrue(distance(glow, background) >= 80, "$glow")
    }

    @Test
    fun `glow on a light fill is darkened instead`() {
        val background = Rgb(240, 240, 235)
        val glow = pickGlowColor(listOf(background), background)
        assertTrue(distance(glow, background) >= 80, "$glow")
        assertTrue(glow.r + glow.g + glow.b < background.r + background.g + background.b)
    }

    @Test
    fun `glow without a palette still differs from the fill`() {
        assertTrue(distance(pickGlowColor(emptyList(), Rgb(30, 30, 30)), Rgb(30, 30, 30)) >= 80)
    }

    // Expected values computed by the desktop's color_extractor.pick_glow_color,
    // so both platforms light the same art the same way.
    @Test
    fun `glow color matches the desktop exactly`() {
        val cases = listOf(
            Triple(listOf(Rgb(12, 12, 14), Rgb(20, 18, 22)), Rgb(12, 12, 14), Rgb(90, 89, 92)),
            Triple(listOf(Rgb(240, 240, 235)), Rgb(240, 240, 235), Rgb(168, 168, 164)),
            Triple(emptyList(), Rgb(30, 30, 30), Rgb(98, 98, 98)),
            Triple(listOf(Rgb(101, 101, 101)), Rgb(100, 100, 100), Rgb(147, 147, 147)),
            Triple(listOf(Rgb(200, 180, 170)), Rgb(205, 185, 175), Rgb(140, 126, 119)),
        )
        cases.forEach { (palette, background, expected) ->
            assertEquals(expected, pickGlowColor(palette, background), "$palette on $background")
        }
    }
}
