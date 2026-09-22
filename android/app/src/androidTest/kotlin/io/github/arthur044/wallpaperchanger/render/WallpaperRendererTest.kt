package io.github.arthur044.wallpaperchanger.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.arthur044.wallpaperchanger.core.config.Settings
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
    fun dominantColorOfASolidImageIsThatColor() {
        // Palette quantizes to 5 bits per channel before clustering, so the
        // swatch is within one quantization step (8) of the true color.
        val found = renderer.dominantColor(solidArt(Color.rgb(200, 30, 60)))
        assertTrue("got $found", abs(found.r - 200) <= 8 && abs(found.g - 30) <= 8 && abs(found.b - 60) <= 8)
    }

    @Test
    fun baseHasTheCanvasSizeAndBackgroundColor() {
        val base = renderer.drawBase(white, layout, gray)
        assertEquals(1080, base.bitmap.width)
        assertEquals(2400, base.bitmap.height)
        assertEquals(gray, base.bitmap.rgbAt(2, 2))
        assertEquals(gray, base.background)
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
