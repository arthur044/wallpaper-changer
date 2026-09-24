package io.github.arthur044.wallpaperchanger.wallpaper

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveWallpaperFramesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "test_live_frames")
    private val file = File(dir, "frame.bin")

    private fun image(color: Int, width: Int = 120, height: Int = 240) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
        setPixel(3, 7, Color.BLUE)
    }

    @Before
    @After
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun nothingPublishedMeansNothingToShow() {
        assertTrue(LiveWallpaperFrames(file).current().isEmpty)
    }

    @Test
    fun aPublishedFrameIsTheCurrentOne() {
        val frames = LiveWallpaperFrames(file)
        frames.publish(image(Color.RED), content = "t1")

        val shown = checkNotNull(frames.current().newest)
        assertEquals(Color.RED, shown.getPixel(60, 120))
        assertEquals(Color.BLUE, shown.getPixel(3, 7))
    }

    @Test
    fun theFrameSurvivesARestart() {
        LiveWallpaperFrames(file).publish(image(Color.GREEN), content = "t1")

        val afterRestart = checkNotNull(LiveWallpaperFrames(file).current().newest)
        assertEquals(120 to 240, afterRestart.width to afterRestart.height)
        assertEquals(Color.GREEN, afterRestart.getPixel(60, 120))
        assertEquals(Color.BLUE, afterRestart.getPixel(3, 7))
    }

    @Test
    fun theCallerMayRecycleItsBitmapRightAfter() {
        val frames = LiveWallpaperFrames(file)
        val source = image(Color.RED)
        frames.publish(source, content = "t1")
        source.recycle()

        assertEquals(Color.RED, checkNotNull(frames.latest.value.newest).getPixel(60, 120))
    }

    @Test
    fun aFoldableKeepsOneFramePerScreen() {
        val frames = LiveWallpaperFrames(file)
        frames.publish(image(Color.RED, 120, 300), content = "t1") // closed: tall
        frames.publish(image(Color.GREEN, 280, 280), content = "t1") // open: square

        assertEquals(Color.RED, checkNotNull(frames.latest.value.bestFor(120, 300)).getPixel(60, 120))
        assertEquals(Color.GREEN, checkNotNull(frames.latest.value.bestFor(280, 240)).getPixel(60, 120))
    }

    @Test
    fun aNewTrackDropsTheOtherScreensFrame() {
        val frames = LiveWallpaperFrames(file)
        frames.publish(image(Color.RED, 120, 300), content = "t1")
        frames.publish(image(Color.GREEN, 280, 280), content = "t1")
        frames.publish(image(Color.BLUE, 120, 300), content = "t2")

        assertEquals(1, frames.latest.value.frames.size)
    }

    @Test
    fun onlyTheNewestFrameSurvivesARestart() {
        val frames = LiveWallpaperFrames(file)
        frames.publish(image(Color.RED, 120, 300), content = "t1")
        frames.publish(image(Color.GREEN, 280, 280), content = "t1")

        val afterRestart = LiveWallpaperFrames(file).current()
        assertEquals(1, afterRestart.frames.size)
        assertEquals(280 to 280, checkNotNull(afterRestart.newest).let { it.width to it.height })
    }

    @Test
    fun anUnreadableFileIsDiscarded() {
        dir.mkdirs()
        file.writeText("not a frame")

        assertTrue(LiveWallpaperFrames(file).current().isEmpty)
        assertTrue(!file.exists())
    }
}
