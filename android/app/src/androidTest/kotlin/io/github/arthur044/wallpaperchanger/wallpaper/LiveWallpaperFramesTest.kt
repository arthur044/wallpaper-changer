package io.github.arthur044.wallpaperchanger.wallpaper

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun image(color: Int) = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888).apply {
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
        assertNull(LiveWallpaperFrames(file).current())
    }

    @Test
    fun aPublishedFrameIsTheCurrentOne() {
        val frames = LiveWallpaperFrames(file)
        frames.publish(image(Color.RED))

        val shown = checkNotNull(frames.current())
        assertEquals(Color.RED, shown.getPixel(60, 120))
        assertEquals(Color.BLUE, shown.getPixel(3, 7))
    }

    @Test
    fun theFrameSurvivesARestart() {
        LiveWallpaperFrames(file).publish(image(Color.GREEN))

        val afterRestart = checkNotNull(LiveWallpaperFrames(file).current())
        assertEquals(120 to 240, afterRestart.width to afterRestart.height)
        assertEquals(Color.GREEN, afterRestart.getPixel(60, 120))
        assertEquals(Color.BLUE, afterRestart.getPixel(3, 7))
    }

    @Test
    fun theCallerMayRecycleItsBitmapRightAfter() {
        val frames = LiveWallpaperFrames(file)
        val source = image(Color.RED)
        frames.publish(source)
        source.recycle()

        assertEquals(Color.RED, checkNotNull(frames.latest.value).getPixel(60, 120))
    }

    @Test
    fun anUnreadableFileIsDiscarded() {
        dir.mkdirs()
        file.writeText("not a frame")

        assertNull(LiveWallpaperFrames(file).current())
        assertTrue(!file.exists())
    }
}
