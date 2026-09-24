package io.github.arthur044.wallpaperchanger.core.render

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlurredArtTest {
    // 64x64 art: red top half, blue bottom half.
    private val redOverBlue = IntArray(64 * 64) { if (it / 64 < 32) Rgb(200, 30, 30).argb else Rgb(30, 40, 200).argb }
    private val gray = IntArray(64 * 64) { Rgb(200, 200, 200).argb }

    private fun IntArray.at(width: Int, x: Int, y: Int) = Rgb.fromArgb(this[y * width + x])

    @Test
    fun `fills the whole canvas with opaque pixels`() {
        val out = blurredArtBackground(gray, 64, 64, 108, 240, 26)
        assertEquals(108 * 240, out.size)
        assertTrue(out.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun `the art covers the canvas, top to top and bottom to bottom`() {
        // Portrait: the square art covers it by height, cropping the sides.
        val out = blurredArtBackground(redOverBlue, 64, 64, 108, 240, 26)
        val top = out.at(108, 54, 20)
        val bottom = out.at(108, 54, 220)
        assertTrue(top.r > top.b, "top should be reddish: $top")
        assertTrue(bottom.b > bottom.r, "bottom should be bluish: $bottom")
    }

    @Test
    fun `edges are darker than the centre`() {
        val out = blurredArtBackground(gray, 64, 64, 200, 200, 26)
        val centre = out.at(200, 100, 100)
        val corner = out.at(200, 1, 1)
        assertTrue(corner.r < centre.r * 0.6, "corner $corner vs centre $centre")
        // The centre still carries the vignette's floor (alpha 50 of 255).
        assertEquals(200 * (1 - 50 / 255.0), centre.r.toDouble(), 3.0)
    }

    @Test
    fun `a stronger blur smooths more`() {
        // Vertical stripes wide enough that the default blur leaves some of
        // them (4 px ones vanish at 26 already, and every strength ties at 0):
        // the weaker the blur, the more survives.
        val stripes = IntArray(64 * 64) { if ((it % 64) % 16 < 8) Rgb(255, 255, 255).argb else Rgb(0, 0, 0).argb }
        fun spread(strength: Int): Double {
            val out = blurredArtBackground(stripes, 64, 64, 400, 400, strength)
            val row = (100 until 300).map { x -> (out[200 * 400 + x] shr 16) and 0xFF }
            val mean = row.average()
            return row.sumOf { (it - mean) * (it - mean) } / row.size
        }
        assertTrue(spread(5) > spread(26), "5 vs 26")
        assertTrue(spread(26) > spread(100), "26 vs 100")
    }

    @Test
    fun `is deterministic`() {
        assertArrayEquals(blurredArtBackground(redOverBlue, 64, 64, 90, 160, 26), blurredArtBackground(redOverBlue, 64, 64, 90, 160, 26))
    }
}
