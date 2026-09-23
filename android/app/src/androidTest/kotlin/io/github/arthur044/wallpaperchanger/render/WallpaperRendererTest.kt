package io.github.arthur044.wallpaperchanger.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.arthur044.wallpaperchanger.core.config.BackgroundStyle
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.config.TextCard
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.render.PixelRect
import io.github.arthur044.wallpaperchanger.core.render.Rgb
import io.github.arthur044.wallpaperchanger.core.render.computeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

// Real Canvas on a real device: pixel checks on synthetic art.
@RunWith(AndroidJUnit4::class)
class WallpaperRendererTest {
    private val renderer = WallpaperRenderer()
    private val phone = CanvasSpec(1080, 2400, PixelRect(0, 63, 1080, 2274), density = 2.625f)
    private val gray = Rgb(128, 128, 128)
    private val white = solidArt(Color.WHITE)
    private val layout = computeLayout(phone, Settings(), sourceArtSidePx = 640)

    private fun solidArt(color: Int) =
        Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun Bitmap.rgbAt(x: Int, y: Int) = Rgb.fromArgb(getPixel(x, y))

    @Test
    fun dominantColorMatchesTheDesktop() {
        // colorthief returns the center of the color's 5-bit histogram cell:
        // (200, 30, 60) -> (204, 28, 60), exactly what the PC computes.
        assertEquals(Rgb(204, 28, 60), renderer.dominantColor(solidArt(Color.rgb(200, 30, 60))))
    }

    @Test
    fun allWhiteArtFallsBackLikeTheDesktop() {
        assertEquals(WallpaperRenderer.FALLBACK_BACKGROUND, renderer.dominantColor(solidArt(Color.WHITE)))
    }

    @Test
    fun baseHasTheCanvasSizeAndBackgroundColor() {
        val base = renderer.drawBase(white, layout, gray)
        assertEquals(1080, base.bitmap.width)
        assertEquals(2400, base.bitmap.height)
        assertEquals(gray, base.bitmap.rgbAt(2, 2))
    }

    @Test
    fun artIsDrawnInItsRect() {
        val base = renderer.drawBase(white, layout, gray)
        assertEquals(Rgb(255, 255, 255), base.bitmap.rgbAt(layout.art.centerX, layout.art.centerY))
    }

    @Test
    fun artCornersAreRounded() {
        val base = renderer.drawBase(white, layout, gray)
        assertNotEquals(Rgb(255, 255, 255), base.bitmap.rgbAt(layout.art.left, layout.art.top))
    }

    @Test
    fun shadowDarkensTheBackgroundBelowTheArt() {
        val base = renderer.drawBase(white, layout, gray)
        val below = base.bitmap.rgbAt(layout.art.centerX, layout.art.bottom + 2)
        assertTrue("expected shadow, got $below", below.r < gray.r)
    }

    @Test
    fun finalImageAddsTextBelowTheArtWithoutTouchingTheBase() {
        val base = renderer.drawBase(white, layout, gray)
        val text = checkNotNull(layout.text)
        val final = renderer.drawFinal(base, layout, "Airbag", "Radiohead")

        assertTrue("title row should have ink", rowHasInk(final, text.titleTop, text.artistTop, gray))
        assertTrue("artist row should have ink", rowHasInk(final, text.artistTop, text.bottom, gray))
        assertTrue("base must stay text-free for reuse", !rowHasInk(base.bitmap, text.titleTop, text.bottom, gray))
    }

    @Test
    fun longTitlesAreEllipsizedWithinTheMaxWidth() {
        val base = renderer.drawBase(white, layout, gray)
        val text = checkNotNull(layout.text)
        val final = renderer.drawFinal(base, layout, "A".repeat(400), "Radiohead")
        val leftLimit = text.centerX - text.maxWidth / 2 - 2
        for (y in text.titleTop until text.artistTop) {
            for (x in 0 until leftLimit) assertEquals("ink at ($x,$y)", gray, final.rgbAt(x, y))
        }
    }

    @Test
    fun noTextLayoutMeansNoText() {
        val artOnly = computeLayout(phone, Settings(showTrackInfo = false), sourceArtSidePx = 640)
        val base = renderer.drawBase(white, artOnly, gray)
        val final = renderer.drawFinal(base, artOnly, "Airbag", "Radiohead")
        assertTrue(!rowHasInk(final, artOnly.art.bottom + 60, final.height - 1, gray))
    }

    @Test
    fun textColorFollowsWhatIsBehindTheTextNotTheFillColor() {
        // Dark fill, but the band under the text is white (as over a gradient
        // or a glow): the text must come out dark to be readable.
        val text = checkNotNull(layout.text)
        val dark = Rgb(10, 10, 10)
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(dark.argb) }
        val band = text.band
        Canvas(bitmap).drawRect(
            band.left.toFloat(), band.top.toFloat(), band.right.toFloat(), band.bottom.toFloat(),
            Paint().apply { color = Color.WHITE },
        )

        val final = renderer.drawFinal(RenderedBase(bitmap), layout, "Airbag", "Radiohead")

        assertTrue("text on a white band should be dark", hasDarkInk(final, band))
    }

    // Mostly gray art (the dominant fill) with a vivid red corner (the accent).
    private fun artWithAccent() = solidArt(Color.rgb(60, 60, 60)).apply {
        Canvas(this).drawRect(0f, 0f, 200f, 200f, Paint().apply { color = Color.rgb(230, 20, 40) })
    }

    private fun redness(p: Rgb) = p.r - p.g

    @Test
    fun glowTintsTheEdgeOfTheArtWithItsColor() {
        val leftOfArt = layout.art.left - 12 to layout.art.centerY
        val plain = renderer.drawBase(white, layout, gray)
        val glow = renderer.drawBase(white, layout, gray, glow = Rgb(230, 20, 40))

        val before = redness(plain.bitmap.rgbAt(leftOfArt.first, leftOfArt.second))
        val after = redness(glow.bitmap.rgbAt(leftOfArt.first, leftOfArt.second))
        assertTrue("glow should push the edge toward red ($before -> $after)", after > before + 60)
    }

    @Test
    fun artGlowSettingLightsTheArtFromItsOwnColors() {
        val leftOfArt = layout.art.left - 12 to layout.art.centerY
        val plain = renderer.renderBase(artWithAccent(), layout, Settings())
        val glow = renderer.renderBase(artWithAccent(), layout, Settings(artGlow = true))

        val before = plain.bitmap.rgbAt(leftOfArt.first, leftOfArt.second)
        val after = glow.bitmap.rgbAt(leftOfArt.first, leftOfArt.second)
        // Same fill either way (the dominant color); only the halo differs.
        assertEquals(plain.bitmap.rgbAt(2, 2), glow.bitmap.rgbAt(2, 2))
        assertTrue("artGlow should light the edge ($before -> $after)", after.r + after.g + after.b > before.r + before.g + before.b + 60)
    }

    @Test
    fun glowReplacesTheDarkShadow() {
        val glow = renderer.renderBase(artWithAccent(), layout, Settings(artGlow = true))
        val corner = glow.bitmap.rgbAt(2, 2)
        val below = glow.bitmap.rgbAt(layout.art.centerX, layout.art.bottom + 2)
        assertTrue("no black shadow under a glowing art ($below vs fill $corner)", below.r >= corner.r)
    }

    private fun corners(bitmap: Bitmap) = listOf(
        bitmap.rgbAt(0, 0), bitmap.rgbAt(bitmap.width - 1, 0),
        bitmap.rgbAt(0, bitmap.height - 1), bitmap.rgbAt(bitmap.width - 1, bitmap.height - 1),
    )

    @Test
    fun meshBackgroundVariesAcrossTheCanvas() {
        val colors = listOf(Rgb(20, 40, 110), Rgb(240, 150, 30), Rgb(30, 190, 150), Rgb(200, 40, 120))
        val base = renderer.drawBase(white, layout, gray, mesh = colors)
        assertTrue("a mesh can't be one flat color: ${corners(base.bitmap)}", corners(base.bitmap).toSet().size > 1)
        assertEquals(Rgb(255, 255, 255), base.bitmap.rgbAt(layout.art.centerX, layout.art.centerY))
    }

    @Test
    fun meshSettingPaintsTheArtsColors() {
        val solid = renderer.renderBase(artWithAccent(), layout, Settings())
        val mesh = renderer.renderBase(artWithAccent(), layout, Settings(backgroundStyle = BackgroundStyle.MESH))
        assertEquals(1, corners(solid.bitmap).toSet().size)
        assertTrue(corners(mesh.bitmap).toSet().size > 1)
    }

    private fun checkerboard() = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(width * height) { i ->
            if (((i % width) / 4 + (i / width) / 4) % 2 == 0) Color.WHITE else Color.BLACK
        }
        setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun redSpread(bitmap: Bitmap, area: PixelRect): Double {
        val reds = (area.top until area.bottom).flatMap { y -> (area.left until area.right).map { x -> bitmap.rgbAt(x, y).r } }
        val mean = reds.average()
        return kotlin.math.sqrt(reds.sumOf { (it - mean) * (it - mean) } / reds.size)
    }

    // The card's top padding: inside the card, above the glyphs. Located with
    // the renderer's own geometry, not guessed coordinates.
    private fun paddingStrip(): PixelRect {
        val ink = checkNotNull(renderer.inkBounds(layout, "Airbag", "Radiohead"))
        val card = checkNotNull(renderer.glassCardFor(layout, "Airbag", "Radiohead")).box
        return PixelRect(ink.left, card.top + 2, ink.right, ink.top - 1).also { assertTrue(it.height >= 3) }
    }

    @Test
    fun glassCardFrostsWhatIsBehindTheText() {
        val base = RenderedBase(checkerboard())
        val strip = paddingStrip()

        val glass = renderer.drawFinal(base, layout, "Airbag", "Radiohead", TextCard.GLASS)
        val plain = renderer.drawFinal(base, layout, "Airbag", "Radiohead", TextCard.NONE)

        assertTrue("the checkerboard should be blurred away", redSpread(glass, strip) < 20)
        assertTrue("without a card the background stays as is", redSpread(plain, strip) > 100)
    }

    @Test
    fun textColorIsJudgedOnTheCard() {
        // Mid-gray reads as dark (light text) on its own; the card's white tint
        // lifts it past the threshold, so the text over it turns dark.
        val base = RenderedBase(Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128, 128, 128)) })
        val band = checkNotNull(layout.text).band

        val plain = renderer.drawFinal(base, layout, "Airbag", "Radiohead", TextCard.NONE)
        val glass = renderer.drawFinal(base, layout, "Airbag", "Radiohead", TextCard.GLASS)

        assertTrue("plain text on mid-gray should be light", !hasDarkInk(plain, band))
        assertTrue("text on the tinted card should be dark", hasDarkInk(glass, band))
    }

    private fun hasDarkInk(bitmap: Bitmap, area: PixelRect): Boolean {
        for (y in area.top until area.bottom) {
            for (x in area.left until area.right step 2) {
                if (bitmap.rgbAt(x, y).r < 100) return true
            }
        }
        return false
    }

    // Any pixel in [top, bottom) noticeably different from the background.
    private fun rowHasInk(bitmap: Bitmap, top: Int, bottom: Int, background: Rgb): Boolean {
        for (y in top until bottom) {
            for (x in 0 until bitmap.width step 2) {
                val p = bitmap.rgbAt(x, y)
                if (abs(p.r - background.r) > 40) return true
            }
        }
        return false
    }
}
