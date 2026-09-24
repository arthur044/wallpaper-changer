package io.github.arthur044.wallpaperchanger.core.render

import kotlin.math.floor
import kotlin.math.sqrt

/*
 * Frosted-glass card behind the track text, the desktop's graphics/glass.py:
 * the pixels behind it blurred, tinted toward white, with a hairline edge.
 *
 * The desktop sizes the card by the screen height, which on a portrait phone
 * would dwarf the text; here it is sized by the title instead, in the same
 * proportions the desktop has at 1080p (title 37 px, padding 32 x 19 px,
 * radius and blur 21 px).
 */

private const val PAD_X_OF_TITLE = 0.865f
private const val PAD_Y_OF_TITLE = 0.514f
private const val RADIUS_OF_TITLE = 0.568f
private const val BLUR_OF_TITLE = 0.568f
private const val TINT_AMOUNT = 0.18
private const val BOX_PASSES = 3

/** Alpha of the white hairline around the card. */
const val GLASS_EDGE_ALPHA = 70

data class GlassCard(val box: PixelRect, val radiusPx: Float, val blurSigmaPx: Float)

/** The card around the text's [ink] box, padded in proportion to the title and kept on the canvas. */
fun glassCard(ink: PixelRect, titleSizePx: Float, canvasWidth: Int, canvasHeight: Int): GlassCard {
    val padX = (titleSizePx * PAD_X_OF_TITLE).toInt()
    val padY = (titleSizePx * PAD_Y_OF_TITLE).toInt()
    val box = PixelRect(
        (ink.left - padX).coerceAtLeast(0),
        (ink.top - padY).coerceAtLeast(0),
        (ink.right + padX).coerceAtMost(canvasWidth),
        (ink.bottom + padY).coerceAtMost(canvasHeight),
    )
    return GlassCard(box, titleSizePx * RADIUS_OF_TITLE, titleSizePx * BLUR_OF_TITLE)
}

/**
 * [pixels] (opaque ARGB, [width] x [height]) blurred with a Gaussian of
 * [sigma] and tinted toward white, as a new array. The Gaussian is three box
 * blurs (edges clamped, so a border never darkens): Android has no Gaussian
 * for a bitmap region before API 31. Plain loops: this runs on every track.
 */
fun frostPixels(pixels: IntArray, width: Int, height: Int, sigma: Float): IntArray {
    require(pixels.size == width * height)
    val channels = Array(3) { c -> IntArray(pixels.size) { (pixels[it] shr (16 - 8 * c)) and 0xFF } }
    blurChannels(channels, width, height, sigma)
    val (r, g, b) = channels
    return IntArray(pixels.size) { (0xFF shl 24) or (tint(r[it]) shl 16) or (tint(g[it]) shl 8) or tint(b[it]) }
}

/** Gaussian blur of [sigma] (three box passes, edges clamped) on each channel, in place. */
internal fun blurChannels(channels: Array<IntArray>, width: Int, height: Int, sigma: Float) {
    if (sigma <= 0f) return
    val scratch = IntArray(width * height)
    for (size in boxSizes(sigma.toDouble())) {
        val radius = (size - 1) / 2
        for (channel in channels) {
            boxBlurRows(channel, scratch, width, height, radius)
            boxBlurColumns(scratch, channel, width, height, radius)
        }
    }
}

private fun tint(c: Int): Int = Math.rint(c + (255 - c) * TINT_AMOUNT).toInt()

// Box widths whose three passes approximate a Gaussian of sigma (Kovesi's method).
private fun boxSizes(sigma: Double): IntArray {
    val ideal = sqrt(12 * sigma * sigma / BOX_PASSES + 1)
    var lower = floor(ideal).toInt()
    if (lower % 2 == 0) lower--
    val upper = lower + 2
    val lowerCount = Math.round(
        (12 * sigma * sigma - BOX_PASSES * lower * lower - 4 * BOX_PASSES * lower - 3 * BOX_PASSES) / (-4.0 * lower - 4),
    ).toInt()
    return IntArray(BOX_PASSES) { if (it < lowerCount) lower else upper }
}

// Running-sum box blur along each row, [src] -> [dst], edges clamped.
private fun boxBlurRows(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
    val size = 2 * radius + 1
    for (y in 0 until height) {
        val row = y * width
        var sum = 0
        for (i in -radius..radius) sum += src[row + i.coerceIn(0, width - 1)]
        for (x in 0 until width) {
            dst[row + x] = (sum + size / 2) / size
            sum += src[row + (x + radius + 1).coerceAtMost(width - 1)] - src[row + (x - radius).coerceAtLeast(0)]
        }
    }
}

// Running-sum box blur down each column, [src] -> [dst], edges clamped.
private fun boxBlurColumns(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
    val size = 2 * radius + 1
    for (x in 0 until width) {
        var sum = 0
        for (i in -radius..radius) sum += src[i.coerceIn(0, height - 1) * width + x]
        for (y in 0 until height) {
            dst[y * width + x] = (sum + size / 2) / size
            sum += src[(y + radius + 1).coerceAtMost(height - 1) * width + x] -
                src[(y - radius).coerceAtLeast(0) * width + x]
        }
    }
}
