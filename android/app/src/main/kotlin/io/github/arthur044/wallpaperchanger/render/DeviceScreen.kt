package io.github.arthur044.wallpaperchanger.render

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import io.github.arthur044.wallpaperchanger.core.render.Insets
import io.github.arthur044.wallpaperchanger.core.render.ScreenMetrics
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

private const val STATUS_BAR_DP = 24
private const val NAV_BAR_DP = 48
