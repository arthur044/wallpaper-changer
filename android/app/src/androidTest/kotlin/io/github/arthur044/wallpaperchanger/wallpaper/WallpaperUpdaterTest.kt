package io.github.arthur044.wallpaperchanger.wallpaper

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.render.PixelRect
import io.github.arthur044.wallpaperchanger.core.sync.WallpaperBlockedException
import io.github.arthur044.wallpaperchanger.render.AlbumBaseCache
import io.github.arthur044.wallpaperchanger.render.WallpaperComposer
import io.github.arthur044.wallpaperchanger.render.WallpaperRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

// The engine's sink: compose for the measured screen, then apply per settings.
@RunWith(AndroidJUnit4::class)
class WallpaperUpdaterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "test_updater_bases")
    private val port = RecordingPort()
    private val settings = MutableStateFlow(Settings())
    private val phone = CanvasSpec(1080, 2400, PixelRect(0, 63, 1080, 2274), density = 2.625f)
    private val unfolded = CanvasSpec(2176, 2176, PixelRect(308, 308, 1868, 1868), density = 2.625f)
    private var screen = phone
    private val png: ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.rgb(200, 60, 40)) }
            .compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()
    private val frames = LiveWallpaperFrames(File(dir, "live/frame.bin"))
    private var liveActive = false
    private val updater = WallpaperUpdater(
        WallpaperComposer({ png }, WallpaperRenderer(), AlbumBaseCache(dir)),
        WallpaperApplier(port),
        settings,
        LiveWallpaper(frames) { liveActive },
    ) { screen }

    private val airbag = NowPlaying(true, "t1", "a1", "https://i.scdn.co/image/a1", "Airbag", "Radiohead")

    @Before
    @After
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun appliesAScreenSizedImageToHomeOnlyByDefault() = runTest {
        updater.show(airbag)

        assertEquals(listOf(WallpaperManager.FLAG_SYSTEM), port.flags)
        assertEquals(Rect(0, 0, 1080, 2400), port.hints.single())
    }

    @Test
    fun lockSyncFromSettingsIsHonored() = runTest {
        settings.value = Settings(syncLockScreen = true)
        updater.show(airbag)

        assertEquals(listOf(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK), port.flags)
    }

    @Test
    fun smoothTransitionHandsTheImageToTheLiveWallpaperInsteadOfTheHomeScreen() = runTest {
        settings.value = Settings(smoothTransition = true)
        liveActive = true

        updater.show(airbag)

        assertTrue("no static home wallpaper: it would blink black", port.flags.isEmpty())
        val frame = checkNotNull(frames.latest.value.newest)
        assertEquals(1080 to 2400, frame.width to frame.height)
    }

    @Test
    fun aFoldableOpenedAndClosedKeepsADrawingForEachScreen() = runTest {
        settings.value = Settings(smoothTransition = true)
        liveActive = true

        updater.show(airbag)
        screen = unfolded
        updater.show(airbag) // the redraw after opening the phone

        val shown = frames.latest.value
        assertEquals(1080 to 2400, checkNotNull(shown.bestFor(1080, 2400)).let { it.width to it.height })
        assertEquals(2176 to 2176, checkNotNull(shown.bestFor(2176, 1812)).let { it.width to it.height })
    }

    @Test
    fun theStaticWallpaperIsRedrawnForTheOpenedScreen() = runTest {
        updater.show(airbag)
        screen = unfolded
        updater.show(airbag)

        assertEquals(listOf(Rect(0, 0, 1080, 2400), Rect(0, 0, 2176, 2176)), port.hints)
    }

    @Test
    fun smoothTransitionStillSetsTheLockScreenStatically() = runTest {
        settings.value = Settings(smoothTransition = true, syncLockScreen = true)
        liveActive = true

        updater.show(airbag)

        assertEquals(listOf(WallpaperManager.FLAG_LOCK), port.flags)
    }

    @Test
    fun untilTheLiveWallpaperIsPickedTheHomeScreenIsStillUpdated() = runTest {
        settings.value = Settings(smoothTransition = true)
        liveActive = false

        updater.show(airbag)

        assertEquals(listOf(WallpaperManager.FLAG_SYSTEM), port.flags)
        assertTrue("ready for when it is picked", !frames.latest.value.isEmpty)
    }

    @Test
    fun withoutSmoothTransitionTheLiveWallpaperIsLeftAlone() = runTest {
        liveActive = true

        updater.show(airbag)

        assertEquals(listOf(WallpaperManager.FLAG_SYSTEM), port.flags)
        assertTrue(frames.latest.value.isEmpty)
    }

    @Test
    fun aPolicyBlockIsPermanent() = runTest {
        port.allowed = false

        val thrown = runCatching { updater.show(airbag) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is WallpaperBlockedException)
    }

    @Test
    fun aRejectedImageIsRetryable() = runTest {
        port.refuse = true

        val thrown = runCatching { updater.show(airbag) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is WallpaperNotAppliedException)
    }

    private class RecordingPort : WallpaperPort {
        var allowed = true
        var refuse = false
        val flags = mutableListOf<Int>()
        val hints = mutableListOf<Rect>()

        override val isSupported = true
        override val isAllowed get() = allowed

        override fun setBitmap(bitmap: Bitmap, visibleCropHint: Rect, which: Int): Int {
            flags += which
            hints += Rect(visibleCropHint)
            return if (refuse) 0 else 1
        }
    }
}
