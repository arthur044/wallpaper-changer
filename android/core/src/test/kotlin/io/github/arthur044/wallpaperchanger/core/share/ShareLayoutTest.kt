package io.github.arthur044.wallpaperchanger.core.share

import io.github.arthur044.wallpaperchanger.core.config.ArtFrame
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.render.PixelRect
import io.github.arthur044.wallpaperchanger.core.render.computeLayout
import io.github.arthur044.wallpaperchanger.core.render.framedArt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.math.abs

class ShareLayoutTest {
    private val a71 = CanvasSpec(1080, 2400, PixelRect(0, 63, 1080, 2274), density = 2.625f)
    private val nineBySixteen = CanvasSpec(1080, 1920, PixelRect(0, 63, 1080, 1794), density = 2.625f)
    private val square = CanvasSpec(2208, 2208, PixelRect(362, 362, 1846, 1846), density = 2.625f)
    private val art = 640

    private fun layout(canvas: CanvasSpec, settings: Settings = Settings()) = shareLayout(canvas, settings, art)

    // --- the card ----------------------------------------------------------

    @Test
    fun `the card sits exactly where the art is drawn, inside the frame`() {
        for (frame in ArtFrame.entries) {
            val settings = Settings(artFrame = frame)
            val wallpaper = computeLayout(a71, settings, art)

            val share = layout(a71, settings)

            assertEquals(framedArt(wallpaper.art, frame), share.card, "$frame")
            assertEquals(wallpaper.cornerRadiusPx, share.cardRadiusPx, "$frame")
        }
    }

    @Test
    fun `the bottom text is the wallpaper's own`() {
        assertEquals(computeLayout(a71, Settings(), art).text, layout(a71).bottomText)
        assertNull(layout(a71, Settings(showTrackInfo = false)).bottomText)
    }

    @Test
    fun `only the density changing keeps the card's size and text, on that base's art`() {
        // The wallpaper's bottom text has dp floors, so a higher density makes
        // it taller and the art moves up or down with the block: the card must
        // follow the art that base shows, but its size and text don't change.
        val lowCanvas = a71.copy(density = 2.0f)
        val highCanvas = a71.copy(density = 3.5f)
        val low = layout(lowCanvas)
        val high = layout(highCanvas)

        assertEquals(framedArt(computeLayout(lowCanvas, Settings(), art).art, ArtFrame.NONE), low.card)
        assertEquals(framedArt(computeLayout(highCanvas, Settings(), art).art, ArtFrame.NONE), high.card)
        assertEquals(low.card.width, high.card.width)
        assertEquals(low.thumb.width, high.thumb.width)
        assertEquals(low.header.titleSizePx, high.header.titleSizePx)
        assertEquals(low.header.artistSizePx, high.header.artistSizePx)
        assertEquals(low.header.maxWidth, high.header.maxWidth)
        assertEquals(low.verses.maxSizePx, high.verses.maxSizePx)
        assertEquals(low.verses.minSizePx, high.verses.minSizePx)
        assertEquals(low.verses.area.height, high.verses.area.height)
        assertEquals(low.verses.area.top - low.card.top, high.verses.area.top - high.card.top)
    }

    @Test
    fun `the card text scales with the card, not the screen`() {
        val big = layout(a71)
        val bigger = layout(a71, Settings(artSizePct = 0.9))
        val ratio = bigger.card.width.toFloat() / big.card.width

        assertEquals(big.verses.maxSizePx * ratio, bigger.verses.maxSizePx, 1f)
        assertEquals(big.header.titleSizePx * ratio, bigger.header.titleSizePx, 1f)
    }

    @ParameterizedTest
    @MethodSource("canvases")
    fun `everything in the card stays inside it`(canvas: CanvasSpec) {
        val share = layout(canvas)
        val card = share.card

        assertTrue(card.contains(share.thumb), "thumb ${share.thumb} in $card")
        assertTrue(share.header.textLeft >= share.thumb.right)
        assertTrue(share.header.textLeft + share.header.maxWidth <= card.right)
        assertTrue(share.verses.area.top >= share.thumb.bottom, "verses below the thumbnail")
        assertTrue(share.verses.area.top >= share.header.bottom, "verses below the header text")
        assertTrue(share.header.bottom <= card.bottom)
        assertTrue(card.contains(share.verses.area), "verses ${share.verses.area} in $card")
        assertTrue(share.verses.minSizePx <= share.verses.maxSizePx)
        assertTrue(share.verses.maxLines >= 1)
    }

    // --- the 9:16 crop -----------------------------------------------------

    @Test
    fun `a 9 by 16 screen is not cropped`() {
        assertEquals(PixelRect(0, 0, 1080, 1920), layout(nineBySixteen).crop)
    }

    @Test
    fun `a square canvas stays square`() {
        assertEquals(PixelRect(0, 0, 2208, 2208), layout(square).crop)
    }

    @ParameterizedTest
    @MethodSource("tallCanvases")
    fun `a taller screen is cropped to 9 by 16 around the card and the text`(canvas: CanvasSpec) {
        val share = layout(canvas)

        assertEquals(canvas.canvasWidth, share.crop.width)
        assertEquals(1920, share.crop.height)
        assertTrue(share.crop.top >= 0 && share.crop.bottom <= canvas.canvasHeight)
        assertTrue(share.crop.containsRows(share.block), "block ${share.block} in crop ${share.crop}")
        // Centered on the block, not on the screen.
        val above = share.block.top - share.crop.top
        val below = share.crop.bottom - share.block.bottom
        assertTrue(abs(above - below) <= 1, "above $above, below $below")
    }

    @Test
    fun `an art offset never crops the card`() {
        for (offset in listOf(-0.25, -0.1, 0.1, 0.25)) {
            val share = layout(a71, Settings(artOffsetYPct = offset))
            assertTrue(share.crop.containsRows(share.block), "offset $offset: block ${share.block} in ${share.crop}")
        }
    }

    @Test
    fun `the block runs from the frame's top to the bottom text`() {
        val settings = Settings(artFrame = ArtFrame.DOUBLE)
        val share = layout(a71, settings)
        val wallpaper = computeLayout(a71, settings, art)

        assertEquals(wallpaper.art.top, share.block.top)
        assertEquals(checkNotNull(wallpaper.text).bottom, share.block.bottom)
    }

    // --- fitting the verses ------------------------------------------------

    @Test
    fun `verses get the largest size at which they fit`() {
        val verses = layout(a71).verses
        assertEquals(verses.maxSizePx, verses.fit { 3 })
    }

    @Test
    fun `verses that wrap more at larger sizes shrink until they fit`() {
        val verses = layout(a71).verses
        // Wider text wraps into more lines; these fit only below some size.
        val lines = { size: Float -> if (size > verses.maxSizePx * 0.8f) verses.maxLines + 5 else 4 }

        val size = checkNotNull(verses.fit(lines))
        assertTrue(size <= verses.maxSizePx * 0.8f)
        assertTrue(size >= verses.minSizePx)
        assertTrue(verses.fits(4, size))
    }

    @Test
    fun `verses that don't fit even at the smallest size don't fit`() {
        val verses = layout(a71).verses
        assertNull(verses.fit { verses.maxLines + 1 })
        assertEquals(verses.minSizePx, verses.fit { size -> if (size > verses.minSizePx) verses.maxLines + 1 else verses.maxLines })
    }

    @Test
    fun `a small card still has a legible floor`() {
        val tiny = layout(a71, Settings(artSizePct = 0.3))
        assertTrue(tiny.verses.minSizePx >= 1080 * 0.028f - 0.5f, "min ${tiny.verses.minSizePx}")
        assertTrue(tiny.header.titleSizePx >= 1080 * 0.022f - 0.5f, "title ${tiny.header.titleSizePx}")
        assertTrue(tiny.verses.maxLines >= 1)
    }

    @Test
    fun `a stanza break at either end of a selection is not drawn`() {
        assertEquals(listOf("a", "", "b"), versesToDraw(listOf("", "a", "", "b", "")))
        assertEquals(emptyList<String>(), versesToDraw(listOf("", "")))
    }

    companion object {
        @JvmStatic
        fun tallCanvases() = listOf(
            CanvasSpec(1080, 2400, PixelRect(0, 63, 1080, 2274), density = 2.625f),
            CanvasSpec(1080, 2520, PixelRect(0, 80, 1080, 2394), density = 2.625f),
        )

        @JvmStatic
        fun canvases() = tallCanvases() + listOf(
            CanvasSpec(1080, 1920, PixelRect(0, 63, 1080, 1794), density = 2.625f),
            CanvasSpec(2208, 2208, PixelRect(362, 362, 1846, 1846), density = 2.625f),
            CanvasSpec(720, 1600, PixelRect(0, 40, 720, 1520), density = 1.75f),
        )
    }
}

private fun PixelRect.contains(other: PixelRect) =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

private fun PixelRect.containsRows(other: PixelRect) = other.top >= top && other.bottom <= bottom
