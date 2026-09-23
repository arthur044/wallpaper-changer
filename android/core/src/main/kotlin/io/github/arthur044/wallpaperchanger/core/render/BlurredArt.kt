package io.github.arthur044.wallpaperchanger.core.render

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The "blur" background, the desktop's renderer._blurred_art_background: the
 * art covering the canvas (center-cropped), blurred moderately (its shapes
 * still read) and darkened toward the edges by a radial vignette.
 *
 * Blurred on a quarter-size copy and upscaled in the same pass that applies
 * the vignette: the blur removed the detail a full-size copy would keep, and
 * one pass over the screen instead of three keeps it cheap. Plain loops.
 */

private const val DOWNSCALE = 4
private const val SIGMA_OF_SHORT = 0.026f
// Pillow's radial_gradient, stretched over the canvas: 0 at the centre, 181
// at the middle of each edge, capped at 255. The shade's alpha is 50 + v / 2.
private const val RADIAL_AT_EDGE = 181.02
private const val VIGNETTE_MIN_ALPHA = 50.0
private const val VIGNETTE_GAIN = 0.5

/** Opaque ARGB pixels of the background, row-major, [width] x [height]. */
fun blurredArtBackground(art: IntArray, artWidth: Int, artHeight: Int, width: Int, height: Int): IntArray {
    require(art.size == artWidth * artHeight && width > 0 && height > 0)
    val smallW = max(1, width / DOWNSCALE)
    val smallH = max(1, height / DOWNSCALE)
    val small = coverSample(art, artWidth, artHeight, smallW, smallH)
    blurChannels(small, smallW, smallH, min(width, height) * SIGMA_OF_SHORT / DOWNSCALE)
    return upscaleWithVignette(small, smallW, smallH, width, height)
}

// The art scaled to cover [w] x [h] and center-cropped, sampled bilinearly, as r/g/b channels.
private fun coverSample(art: IntArray, artW: Int, artH: Int, w: Int, h: Int): Array<IntArray> {
    val scale = max(w.toDouble() / artW, h.toDouble() / artH)
    val left = (artW - w / scale) / 2
    val top = (artH - h / scale) / 2
    val out = Array(3) { IntArray(w * h) }
    for (y in 0 until h) {
        val sy = (top + (y + 0.5) / scale - 0.5).coerceIn(0.0, artH - 1.0)
        val y0 = floor(sy).toInt()
        val y1 = min(y0 + 1, artH - 1)
        val fy = sy - y0
        for (x in 0 until w) {
            val sx = (left + (x + 0.5) / scale - 0.5).coerceIn(0.0, artW - 1.0)
            val x0 = floor(sx).toInt()
            val x1 = min(x0 + 1, artW - 1)
            val fx = sx - x0
            val p00 = art[y0 * artW + x0]
            val p01 = art[y0 * artW + x1]
            val p10 = art[y1 * artW + x0]
            val p11 = art[y1 * artW + x1]
            for (c in 0..2) {
                val shift = 16 - 8 * c
                val upper = ((p00 shr shift) and 0xFF) * (1 - fx) + ((p01 shr shift) and 0xFF) * fx
                val lower = ((p10 shr shift) and 0xFF) * (1 - fx) + ((p11 shr shift) and 0xFF) * fx
                out[c][y * w + x] = (upper * (1 - fy) + lower * fy + 0.5).toInt()
            }
        }
    }
    return out
}

private fun upscaleWithVignette(small: Array<IntArray>, smallW: Int, smallH: Int, width: Int, height: Int): IntArray {
    val (r, g, b) = small
    val out = IntArray(width * height)
    val halfW = width / 2.0
    val halfH = height / 2.0
    for (y in 0 until height) {
        val sy = ((y + 0.5) * smallH / height - 0.5).coerceIn(0.0, smallH - 1.0)
        val y0 = floor(sy).toInt()
        val y1 = min(y0 + 1, smallH - 1)
        val fy = sy - y0
        val dy = (y + 0.5 - halfH) / halfH
        for (x in 0 until width) {
            val sx = ((x + 0.5) * smallW / width - 0.5).coerceIn(0.0, smallW - 1.0)
            val x0 = floor(sx).toInt()
            val x1 = min(x0 + 1, smallW - 1)
            val fx = sx - x0
            val dx = (x + 0.5 - halfW) / halfW
            val radial = min(255.0, RADIAL_AT_EDGE * sqrt(dx * dx + dy * dy))
            val keep = 1 - min(255.0, VIGNETTE_MIN_ALPHA + VIGNETTE_GAIN * radial) / 255.0
            val i00 = y0 * smallW + x0
            val i01 = y0 * smallW + x1
            val i10 = y1 * smallW + x0
            val i11 = y1 * smallW + x1
            val red = bilinear(r[i00], r[i01], r[i10], r[i11], fx, fy) * keep
            val green = bilinear(g[i00], g[i01], g[i10], g[i11], fx, fy) * keep
            val blue = bilinear(b[i00], b[i01], b[i10], b[i11], fx, fy) * keep
            out[y * width + x] = (0xFF shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
        }
    }
    return out
}

private fun bilinear(p00: Int, p01: Int, p10: Int, p11: Int, fx: Double, fy: Double): Double =
    (p00 * (1 - fx) + p01 * fx) * (1 - fy) + (p10 * (1 - fx) + p11 * fx) * fy

private fun channel(v: Double): Int = (v + 0.5).toInt().coerceIn(0, 255)
