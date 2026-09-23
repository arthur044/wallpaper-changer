package io.github.arthur044.wallpaperchanger.core.render

/** Integer pixel rectangle, right/bottom exclusive (like android.graphics.Rect). */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/** System bar / cutout insets in pixels, as reported for the current orientation. */
data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val largest: Int get() = maxOf(left, top, right, bottom)
}
