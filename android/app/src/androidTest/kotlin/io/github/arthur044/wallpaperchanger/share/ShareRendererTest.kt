package io.github.arthur044.wallpaperchanger.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.config.BackgroundStyle
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.render.PixelRect
import io.github.arthur044.wallpaperchanger.core.render.computeLayout
import io.github.arthur044.wallpaperchanger.core.share.shareLayout
import io.github.arthur044.wallpaperchanger.render.CachedBase
import io.github.arthur044.wallpaperchanger.render.WallpaperRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// The share image on each background, from a base like the wallpaper's.
// Samples land in files/share_samples for a look by eye (adb pull).
@RunWith(AndroidJUnit4::class)
class ShareRendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val renderer = WallpaperRenderer()
    private val share = ShareRenderer(renderer)
    private val a71 = CanvasSpec(1080, 2400, PixelRect(0, 63, 1080, 2274), density = 2.625f)
    private val playing = NowPlaying(true, "t1", "a1", null, "A Rather Long Track Title That Needs Cutting", "Some Artist")
    private val verses = listOf("Placeholder line one", "Placeholder line two", "", "Placeholder line three")
    private val filesDir = File(context.cacheDir, "test_share")

    @After
    fun clean() {
        filesDir.deleteRecursively()
    }

    // Two colors, so the blur and the mesh have something to work with.
    private fun art(): Bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(40, 90, 160))
        Canvas(this).drawCircle(420f, 220f, 180f, Paint().apply { color = Color.rgb(230, 170, 40) })
    }

    private fun base(settings: Settings): CachedBase {
        val art = art()
        val layout = computeLayout(a71, settings, art.width)
        return CachedBase(renderer.renderBase(art, layout, settings), art.width).also { art.recycle() }
    }

    @Test
    fun eachBackgroundGivesACroppedImageWithTheCardDrawnOverTheArt() {
        val samples = File(context.filesDir, "share_samples").apply { mkdirs() }
        for (style in BackgroundStyle.entries) {
            val settings = Settings(backgroundStyle = style)
            val base = base(settings)
            val layout = shareLayout(a71, settings, base.sourceArtSidePx)

            val image = share.draw(base, layout, playing, verses, settings.textCard)

            assertEquals("$style width", layout.crop.width, image.width)
            assertEquals("$style height", layout.crop.height, image.height)
            val crop = layout.crop
            val card = layout.card
            // Inside the card the 42 % black scrim darkens whatever the art
            // was (the synthetic art has no black); above the block the image
            // is the base, untouched and shifted by the crop.
            val x = card.centerX
            val y = card.bottom - 8
            assertNotEquals("$style card", base.base.bitmap.getPixel(x, y), image.getPixel(x - crop.left, y - crop.top))
            assertEquals("$style background", base.base.bitmap.getPixel(10, crop.top + 10), image.getPixel(10, 10))
            assertFalse("the base is the caller's", base.base.bitmap.isRecycled)

            File(samples, "share_${style.name.lowercase()}.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
            base.base.bitmap.recycle()
        }
    }

    @Test
    fun theSelectionIsLimitedToWhatFits() {
        val settings = Settings()
        val base = base(settings)
        val layout = shareLayout(a71, settings, base.sourceArtSidePx)

        assertTrue(share.versesFit(layout, verses))
        assertFalse(share.versesFit(layout, List(layout.verses.maxLines + 1) { "Placeholder line $it" }))
        val thrown = runCatching { share.draw(base, layout, playing, List(60) { "x" }, settings.textCard) }.exceptionOrNull()
        assertTrue("got $thrown", thrown is IllegalArgumentException)
        base.base.bitmap.recycle()
    }

    @Test
    fun theSavedImageDecodesAndIsTheOnlyFile() {
        val settings = Settings()
        val base = base(settings)
        val layout = shareLayout(a71, settings, base.sourceArtSidePx)
        val files = ShareFiles(filesDir)
        val image = share.draw(base, layout, playing, verses, settings.textCard)

        files.write(image, ShareFormat.JPEG)
        Thread.sleep(2)
        val file = files.write(image, ShareFormat.JPEG)

        assertEquals(listOf(file), filesDir.listFiles().orEmpty().toList())
        val decoded = checkNotNull(BitmapFactory.decodeFile(file.path)) { "not a decodable JPEG" }
        assertEquals(image.width, decoded.width)
        assertEquals(image.height, decoded.height)
        image.recycle()
        base.base.bitmap.recycle()
    }
}
