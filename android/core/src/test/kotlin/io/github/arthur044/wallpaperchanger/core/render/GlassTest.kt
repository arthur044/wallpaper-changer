package io.github.arthur044.wallpaperchanger.core.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class GlassTest {
    private fun checkerboard(width: Int, height: Int, cell: Int = 4) = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        if ((x / cell + y / cell) % 2 == 0) Rgb(255, 255, 255).argb else Rgb(0, 0, 0).argb
    }

    private fun reds(pixels: IntArray) = pixels.map { (it shr 16) and 0xFF }

    private fun stddev(values: List<Int>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    @Test
    fun `card pads the ink box in proportion to the title`() {
        val ink = PixelRect(300, 1500, 780, 1600)
        val card = glassCard(ink, titleSizePx = 48f, canvasWidth = 1080, canvasHeight = 2400)

        assertTrue(card.box.left < ink.left && card.box.right > ink.right)
        assertTrue(card.box.top < ink.top && card.box.bottom > ink.bottom)
        // Same proportions as the desktop card at 1080p (title 37 px, pad 32 x 19).
        assertEquals(ink.left - 41, card.box.left)
        assertEquals(ink.top - 24, card.box.top)
        assertEquals(27f, card.radiusPx, 0.5f)
        assertEquals(27f, card.blurSigmaPx, 0.5f)
    }

    @Test
    fun `card stays on the canvas`() {
        val card = glassCard(PixelRect(10, 5, 1070, 2395), titleSizePx = 48f, canvasWidth = 1080, canvasHeight = 2400)
        assertEquals(PixelRect(0, 0, 1080, 2400), card.box)
    }

    @Test
    fun `frost blurs away fine detail`() {
        val (w, h) = 64 to 64
        val frosted = frostPixels(checkerboard(w, h), w, h, sigma = 6f)

        assertTrue(stddev(reds(checkerboard(w, h))) > 100)
        assertTrue(stddev(reds(frosted)) < 12, "stddev ${stddev(reds(frosted))}")
    }

    @Test
    fun `frost tints 18 percent toward white`() {
        val (w, h) = 16 to 16
        val flat = IntArray(w * h) { Rgb(100, 100, 100).argb }
        // 100 + (255 - 100) * 0.18 = 127.9
        assertTrue(frostPixels(flat, w, h, sigma = 4f).all { it == Rgb(128, 128, 128).argb })
    }

    @Test
    fun `frost keeps the average, then tints it`() {
        val (w, h) = 64 to 64
        val mean = reds(frostPixels(checkerboard(w, h), w, h, sigma = 6f)).average()
        // Checkerboard mean 127.5, tinted: 127.5 + 127.5 * 0.18 = 150.45.
        assertEquals(150.45, mean, 1.5)
    }

    @Test
    fun `frost clamps at the edges instead of darkening them`() {
        val (w, h) = 20 to 20
        val flat = IntArray(w * h) { Rgb(200, 30, 60).argb }
        val frosted = frostPixels(flat, w, h, sigma = 8f)
        assertEquals(frosted[0], frosted[w * h / 2 + w / 2])
    }

    @Test
    fun `frost returns new pixels and leaves the input alone`() {
        val input = checkerboard(8, 8)
        val copy = input.copyOf()
        frostPixels(input, 8, 8, sigma = 2f)
        assertTrue(input.contentEquals(copy))
    }
}
