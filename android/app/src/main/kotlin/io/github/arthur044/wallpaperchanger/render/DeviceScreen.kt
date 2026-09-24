package io.github.arthur044.wallpaperchanger.render

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import kotlin.math.min
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
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val metrics = windowManager.maximumWindowMetrics
        val smallestWidthDp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            resources.configuration.smallestScreenWidthDp
        } else {
            // Before S a window context gets no configuration updates: its
            // resources keep the screen it was created on (a foldable opened
            // since would still read as a phone). The window bounds do follow.
            // This counts the whole screen, while the system's value leaves the
            // nav bar out: only a screen within a bar's width of 600dp differs.
            (min(metrics.bounds.width(), metrics.bounds.height()) / density).toInt()
        }
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
            smallestWidthDp = resources.configuration.smallestScreenWidthDp,
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
 * The canvas for this screen, now and whenever the main display or the
 * configuration changes: a foldable opened or closed changes the screen
 * without any track changing. Use on a context from
 * [defaultDisplayWindowContext].
 *
 * From S on, the window context's configuration arrives with the new bounds.
 * Before S it gets no configuration updates (its callbacks go to the
 * application, maybe before the bounds change), so the main display is
 * watched too. Only there: from S on, onDisplayChanged also fires for refresh
 * rate changes, many times a second while scrolling on an adaptive screen.
 */
fun Context.canvasSpecs(): Flow<CanvasSpec> = callbackFlow {
    val measure = { trySend(canvasSpec(screenMetrics())) }
    val callbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            measure()
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }
    val displays = getSystemService(DisplayManager::class.java).takeIf { Build.VERSION.SDK_INT < Build.VERSION_CODES.S }
    val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) measure()
        }

        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit
    }
    registerComponentCallbacks(callbacks)
    displays?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    // Read after registering, so a change in between is not lost.
    measure()
    awaitClose {
        displays?.unregisterDisplayListener(listener)
        unregisterComponentCallbacks(callbacks)
    }
}

private const val STATUS_BAR_DP = 24
private const val NAV_BAR_DP = 48
