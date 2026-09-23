package io.github.arthur044.wallpaperchanger.core.render

import kotlin.math.max
import kotlin.math.min

/** What the device reports about its screen right now. */
data class ScreenMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val smallestWidthDp: Int,
    val insets: Insets,
)

/**
 * The bitmap to render and the part of it guaranteed to be visible. Content
 * (art and text) must stay inside [safeArea]; the rest is background.
 */
data class CanvasSpec(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val safeArea: PixelRect,
    val density: Float,
)

/** Android's own phone/tablet split (sw600dp). */
const val LARGE_SCREEN_MIN_WIDTH_DP = 600

/**
 * Phones keep a portrait wallpaper whatever the current rotation, so the
 * canvas is the screen in portrait minus the system bars.
 *
 * Tablets and unfolded foldables show the same wallpaper in both
 * orientations, center-cropped. The bitmap is therefore a square of the long
 * side, and content goes in the centered square of the short side: the only
 * region visible both ways. That square is shrunk on every side by the largest
 * system bar, because a bar that is at the bottom in one orientation can sit on
 * a side in the other.
 */
fun canvasSpec(screen: ScreenMetrics): CanvasSpec {
    require(screen.widthPx > 0 && screen.heightPx > 0) { "screen must have a size, was ${screen.widthPx}x${screen.heightPx}" }
    return if (screen.smallestWidthDp >= LARGE_SCREEN_MIN_WIDTH_DP) squareSpec(screen) else portraitSpec(screen)
}

private fun portraitSpec(screen: ScreenMetrics): CanvasSpec {
    val width = min(screen.widthPx, screen.heightPx)
    val height = max(screen.widthPx, screen.heightPx)
    val insets = screen.insets
    val (top, bottom, left, right) = if (screen.widthPx <= screen.heightPx) {
        listOf(insets.top, insets.bottom, insets.left, insets.right)
    } else {
        // Measured in landscape: the status bar stays on top, while the nav bar
        // (on a side now) returns to the bottom in portrait.
        listOf(insets.top, maxOf(insets.bottom, insets.left, insets.right), 0, 0)
    }
    return CanvasSpec(width, height, nonEmpty(PixelRect(left, top, width - right, height - bottom), width, height), screen.density)
}

private fun squareSpec(screen: ScreenMetrics): CanvasSpec {
    val side = max(screen.widthPx, screen.heightPx)
    val short = min(screen.widthPx, screen.heightPx)
    val offset = (side - short) / 2
    val inset = screen.insets.largest
    val safe = PixelRect(offset + inset, offset + inset, offset + short - inset, offset + short - inset)
    return CanvasSpec(side, side, nonEmpty(safe, side, side), screen.density)
}

// Absurd insets must not produce an empty or inverted safe area.
private fun nonEmpty(rect: PixelRect, width: Int, height: Int): PixelRect =
    if (rect.width > 0 && rect.height > 0) rect else PixelRect(0, 0, width, height)
