package io.github.arthur044.wallpaperchanger.wallpaper

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

// Runs against a fake WallpaperManager: a real one would replace the wallpaper
// of whatever phone runs the suite. The real path is checked by hand (M7).
@RunWith(AndroidJUnit4::class)
class WallpaperApplierTest {
    private val scope = TestScope()
    private val port = FakePort()
    private val applier = WallpaperApplier(port, StandardTestDispatcher(scope.testScheduler))
    private val image = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)

    @Test
    fun homeOnlyLeavesTheLockScreenAlone() = scope.runTest {
        assertEquals(ApplyResult.Applied, applier.apply(image, includeLockScreen = false))
        assertEquals(listOf(WallpaperManager.FLAG_SYSTEM), port.calls.map { it.which })
    }

    @Test
    fun syncingTheLockScreenSetsBoth() = scope.runTest {
        applier.apply(image, includeLockScreen = true)
        assertEquals(
            listOf(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
            port.calls.map { it.which },
        )
    }

    @Test
    fun theWholeImageIsTheVisiblePart() = scope.runTest {
        applier.apply(image, includeLockScreen = false)
        assertEquals(Rect(0, 0, 1080, 2400), port.calls.single().hint)
        assertSame(image, port.calls.single().bitmap)
    }

    @Test
    fun anUnsupportedDeviceIsNeverTouched() = scope.runTest {
        port.isSupported = false
        assertEquals(ApplyResult.Unsupported, applier.apply(image, includeLockScreen = true))
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun aDevicePolicyBlockIsReportedWithoutTrying() = scope.runTest {
        port.isAllowed = false
        assertEquals(ApplyResult.NotAllowed, applier.apply(image, includeLockScreen = false))
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun anIoErrorIsAFailureNotACrash() = scope.runTest {
        val error = IOException("disk full")
        port.error = error
        assertEquals(ApplyResult.Failed(error), applier.apply(image, includeLockScreen = false))
    }

    @Test
    fun aMissingPermissionIsAFailureNotACrash() = scope.runTest {
        val error = SecurityException("no SET_WALLPAPER")
        port.error = error
        assertEquals(ApplyResult.Failed(error), applier.apply(image, includeLockScreen = false))
    }

    @Test
    fun aZeroIdMeansTheSystemRefused() = scope.runTest {
        port.nextId = 0
        assertTrue(applier.apply(image, includeLockScreen = false) is ApplyResult.Failed)
    }

    private class Call(val bitmap: Bitmap, val hint: Rect, val which: Int)

    private class FakePort : WallpaperPort {
        override var isSupported = true
        override var isAllowed = true
        var nextId = 7
        var error: Exception? = null
        val calls = mutableListOf<Call>()

        override fun setBitmap(bitmap: Bitmap, visibleCropHint: Rect, which: Int): Int {
            error?.let { throw it }
            calls += Call(bitmap, visibleCropHint, which)
            return nextId
        }
    }
}
