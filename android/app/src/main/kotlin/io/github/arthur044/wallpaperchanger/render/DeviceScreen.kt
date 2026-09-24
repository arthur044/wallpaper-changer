package io.github.arthur044.wallpaperchanger.render

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.render.Insets
import io.github.arthur044.wallpaperchanger.core.render.ScreenMetrics
import io.github.arthur044.wallpaperchanger.core.render.canvasSpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.roundToInt

/**
 * The full physical screen (not the app window) and its system bars, for
 * [io.github.arthur044.wallpaperchanger.core.render.canvasSpec].
 *
 * Needs a visual context (an Activity, or a window context in a service):
 * window metrics are undefined for the plain application context.
 */
fun Context.screenMetrics(): ScreenMetrics {
    val windowManager = getSystemService(WindowManager::class.java)
    val density = resources.displayMetrics.density
    val smallestWidthDp = resources.configuration.smallestScreenWidthDp
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val metrics = windowManager.maximumWindowMetrics
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        ScreenMetrics(
            widthPx = metrics.bounds.width(),
            heightPx = metrics.bounds.height(),
            density = density,
            smallestWidthDp = smallestWidthDp,
            insets = Insets(bars.left, bars.top, bars.right, bars.bottom),
        )
    } else {
        val real = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(real)
        // No reliable inset API before R: assume the standard bar heights.
        ScreenMetrics(
            widthPx = real.widthPixels,
            heightPx = real.heightPixels,
            density = density,
            smallestWidthDp = smallestWidthDp,
            insets = Insets(0, (STATUS_BAR_DP * density).roundToInt(), 0, (NAV_BAR_DP * density).roundToInt()),
        )
    }
}

/**
 * A visual context on the main display for [screenMetrics] outside an
 * Activity (the sync service). Create once and keep: each one is a window token.
 */
fun Context.defaultDisplayWindowContext(): Context {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return this
    val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    // Only used to read metrics: no view is ever added, so no overlay permission is needed.
    return createDisplayContext(display)
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
}

/**
 * The canvas for this screen, now and after every configuration change: a
 * foldable opened or closed changes the screen without any track changing.
 * Use on a context from [defaultDisplayWindowContext], which follows its display.
 */
fun Context.canvasSpecs(): Flow<CanvasSpec> = callbackFlow {
    val callbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            trySend(canvasSpec(screenMetrics()))
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }
    registerComponentCallbacks(callbacks)
    // Read after registering, so a change in between is not lost.
    trySend(canvasSpec(screenMetrics()))
    awaitClose { unregisterComponentCallbacks(callbacks) }
}

private const val STATUS_BAR_DP = 24
private const val NAV_BAR_DP = 48
