package io.github.arthur044.wallpaperchanger.core.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// Golden values come from the desktop's colorthief 0.2.1 run on the exact same
// pixels (same generator, ported from the Python script that produced them),
// so any drift from the PC's background color fails here. Quirks included:
// asking for 10 colors yields 9, and empty boxes average to their midpoint.
class ColorThiefTest {

    @Test
    fun `light cover with a dark subject picks the light background`() {
        assertPalette(cover(), 5, 4, "(229, 204, 194), (33, 36, 59), (32, 23, 59), (229, 218, 196), (229, 218, 180)")
        assertPalette(
            cover(), 10, 1,
            "(229, 204, 194), (32, 36, 59), (32, 23, 59), (229, 219, 180), (223, 219, 195), " +
                "(212, 219, 196), (236, 218, 195), (244, 218, 197), (227, 218, 212)",
        )
    }

    @Test
    fun `random noise`() {
        assertPalette(noise(), 5, 4, "(69, 160, 94), (224, 128, 128), (96, 31, 127), (93, 164, 223), (167, 161, 96)")
        assertPalette(
            noise(), 10, 1,
            "(224, 95, 129), (71, 71, 72), (96, 224, 159), (95, 96, 223), (167, 94, 96), " +
                "(70, 168, 95), (223, 224, 130), (72, 71, 167), (95, 223, 31)",
        )
    }

    @Test
    fun `smooth gradient with noise`() {
        assertPalette(gradient(), 5, 4, "(183, 184, 127), (29, 128, 127), (157, 32, 127), (87, 160, 127), (183, 88, 127)")
        assertPalette(
            gradient(), 10, 1,
            "(160, 32, 128), (32, 160, 127), (212, 200, 128), (88, 160, 128), (184, 88, 127), " +
                "(128, 184, 128), (32, 32, 127), (200, 128, 128), (156, 200, 128)",
        )
    }

    @Test
    fun `near-white and translucent pixels are ignored`() {
        assertPalette(paper(), 5, 4, "(7, 127, 147), (10, 148, 145), (9, 131, 132), (20, 132, 147), (8, 140, 148)")
        assertPalette(
            paper(), 10, 1,
            "(10, 148, 140), (20, 131, 147), (7, 140, 147), (7, 131, 132), (4, 127, 143), " +
                "(12, 127, 143), (7, 128, 156), (9, 148, 156), (20, 132, 132)",
        )
    }

    @Test
    fun `a solid color comes back as its histogram cell center`() {
        assertPalette(solid(), 5, 4, "(204, 28, 28), (208, 28, 28), (208, 28, 28), (208, 28, 28), (208, 28, 28)")
        assertEquals(Rgb(204, 28, 28), ColorThief.dominantColor(solid()))
    }

    @Test
    fun `dominant color is the first palette entry at the desktop's quality`() {
        assertEquals(Rgb(229, 204, 194), ColorThief.dominantColor(cover()))
    }

    @Test
    fun `an all-white image has no color, so the caller falls back`() {
        assertNull(ColorThief.dominantColor(IntArray(W * H) { argb(255, 255, 255, 255) }))
        assertNull(ColorThief.dominantColor(IntArray(0)))
    }

    private fun assertPalette(pixels: IntArray, count: Int, quality: Int, expected: String) {
        val actual = ColorThief.palette(pixels, count, quality)?.joinToString { "(${it.r}, ${it.g}, ${it.b})" }
        assertEquals(expected, actual)
    }

    private class Lcg(private var state: Long) {
        fun byte(): Int {
            state = (state * 1103515245L + 12345L) and 0x7FFFFFFFL
            return ((state shr 16) and 0xFF).toInt()
        }
    }

    private fun cover(): IntArray {
        val rng = Lcg(1)
        return pixels { x, y, _ ->
            if (x in 40 until 120 && y in 40 until 120) {
                argb(255, 20 + rng.byte() % 25, 15 + rng.byte() % 25, 40 + rng.byte() % 40)
            } else {
                argb(255, 215 + rng.byte() % 30, 200 + rng.byte() % 30, 180 + rng.byte() % 30)
            }
        }
    }

    private fun noise(): IntArray {
        val rng = Lcg(2)
        return pixels { _, _, _ -> argb(255, rng.byte(), rng.byte(), rng.byte()) }
    }

    private fun gradient(): IntArray {
        val rng = Lcg(3)
        return pixels { x, y, _ -> argb(255, x * 255 / (W - 1), y * 255 / (H - 1), 96 + rng.byte() % 64) }
    }

    private fun paper(): IntArray {
        val rng = Lcg(4)
        return pixels { _, _, i ->
            val alpha = if (i % 7 == 0) 100 else 255
            if (rng.byte() < 154) {
                argb(alpha, 251 + rng.byte() % 5, 251 + rng.byte() % 5, 251 + rng.byte() % 5)
            } else {
                argb(alpha, rng.byte() % 20, 120 + rng.byte() % 30, 130 + rng.byte() % 30)
            }
        }
    }

    private fun solid() = IntArray(W * H) { argb(255, 200, 30, 30) }

    // Row-major, like Pillow's getdata() and Android's Bitmap.getPixels().
    private fun pixels(at: (x: Int, y: Int, index: Int) -> Int): IntArray {
        val out = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) out[y * W + x] = at(x, y, y * W + x)
        return out
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private companion object {
        const val W = 160
        const val H = 160
    }
}
