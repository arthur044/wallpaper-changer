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
    private val png: ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.rgb(200, 60, 40)) }
            .compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()
    private val updater = WallpaperUpdater(
        WallpaperComposer({ png }, WallpaperRenderer(), AlbumBaseCache(dir)),
        WallpaperApplier(port),
        settings,
    ) { phone }

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
    fun aWallpaperThatWasNotAppliedIsAnError() = runTest {
        port.allowed = false

        val thrown = runCatching { updater.show(airbag) }.exceptionOrNull()

        assertTrue(thrown is WallpaperNotAppliedException)
        assertEquals(ApplyResult.NotAllowed, (thrown as WallpaperNotAppliedException).result)
    }

    private class RecordingPort : WallpaperPort {
        var allowed = true
        val flags = mutableListOf<Int>()
        val hints = mutableListOf<Rect>()

        override val isSupported = true
        override val isAllowed get() = allowed

        override fun setBitmap(bitmap: Bitmap, visibleCropHint: Rect, which: Int): Int {
            flags += which
            hints += Rect(visibleCropHint)
            return 1
        }
    }
}
