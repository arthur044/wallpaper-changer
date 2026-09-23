package io.github.arthur044.wallpaperchanger.core.render

import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.pow

/*
 * Mesh gradient background, a port of the desktop's graphics/mesh.py: a few of
 * the art's colors as soft blobs, blended in OKLab (so neighbouring hues meet
 * in a clean mid-tone instead of RGB's gray) on a small float grid, upscaled
 * with Pillow's bicubic resampling and rounded once, at full size, through an
 * 8x8 Bayer dither. Rounding before the upscale leaves blocky plateaus in dark
 * gradients. The arithmetic follows Pillow step for step (float32 storage,
 * double accumulation, truncating float->8-bit) so both platforms agree to
 * within one level.
 */

private const val GRID_WIDTH = 64

// Blob centers for colors[1:], as fractions of the canvas. colors[0] (the
// dominant) is the base everywhere, so the art sits on it.
private val ANCHORS = arrayOf(0.12 to 0.18, 0.88 to 0.22, 0.80 to 0.90, 0.18 to 0.86)
private const val BLOB_SIGMA = 0.28
private const val BASE_WEIGHT = 0.25

private val BAYER_8 = arrayOf(
    intArrayOf(0, 32, 8, 40, 2, 34, 10, 42),
    intArrayOf(48, 16, 56, 24, 50, 18, 58, 26),
    intArrayOf(12, 44, 4, 36, 14, 46, 6, 38),
    intArrayOf(60, 28, 52, 20, 62, 30, 54, 22),
    intArrayOf(3, 35, 11, 43, 1, 33, 9, 41),
    intArrayOf(51, 19, 59, 27, 49, 17, 57, 25),
    intArrayOf(15, 47, 7, 39, 13, 45, 5, 37),
    intArrayOf(63, 31, 55, 23, 61, 29, 53, 21),
)

// Thresholds in (0, 1): floor(value + threshold) rounds on average.
private val BAYER_THRESHOLDS = Array(8) { y -> FloatArray(8) { x -> ((BAYER_8[y][x] + 0.5) / 64).toFloat() } }

/** The mesh as opaque ARGB pixels, row-major. colors[0] is the base; up to 4 more are blobs. */
fun meshPixels(width: Int, height: Int, colors: List<Rgb>): IntArray {
    require(width > 0 && height > 0 && colors.isNotEmpty())
    if (colors.size < 2) return IntArray(width * height) { colors[0].argb }

    val gridW = GRID_WIDTH
    val gridH = maxOf(2, Math.rint(GRID_WIDTH.toDouble() * height / width).toInt())
    // Pillow's two-pass BICUBIC resize: horizontal first (grid rows to full
    // width, small), then vertical, fused here with the dither and the packing
    // so no full-size float image is ever allocated. Plain loops on purpose: a
    // lambda per sample boxes every value and took 10 s for a phone screen.
    val rows = meshGrid(gridW, gridH, colors).map { horizontalPass(it, gridW, gridH, width) }
    val (r, g, b) = rows
    val vertical = Coefficients(gridH, height)
    val out = IntArray(width * height)
    for (y in 0 until height) {
        val k = vertical.weights[y]
        val start = vertical.starts[y]
        val thresholds = BAYER_THRESHOLDS[y % 8]
        for (x in 0 until width) {
            var sr = 0.0
            var sg = 0.0
            var sb = 0.0
            for (i in k.indices) {
                val at = (start + i) * width + x
                sr += r[at] * k[i]
                sg += g[at] * k[i]
                sb += b[at] * k[i]
            }
            val t = thresholds[x % 8]
            out[y * width + x] = (0xFF shl 24) or
                (toByte(sr.toFloat() + t) shl 16) or
                (toByte(sg.toFloat() + t) shl 8) or
                toByte(sb.toFloat() + t)
        }
    }
    return out
}

// Each grid row resampled to [dstW] columns, still float and still gridH rows.
private fun horizontalPass(src: FloatArray, srcW: Int, srcH: Int, dstW: Int): FloatArray {
    val coefficients = Coefficients(srcW, dstW)
    val out = FloatArray(dstW * srcH)
    for (y in 0 until srcH) {
        val row = y * srcW
        for (x in 0 until dstW) {
            val k = coefficients.weights[x]
            val start = coefficients.starts[x]
            var acc = 0.0
            for (i in k.indices) acc += src[row + start + i] * k[i]
            out[y * dstW + x] = acc.toFloat()
        }
    }
    return out
}

// Pillow's F -> L conversion: clip, then truncate.
private fun toByte(v: Float): Int = when {
    v <= 0f -> 0
    v >= 255f -> 255
    else -> v.toInt()
}

/** The blended mesh at grid resolution: sRGB r, g, b on the 0..255 scale, unrounded. */
internal fun meshGrid(gridW: Int, gridH: Int, colors: List<Rgb>): List<FloatArray> {
    val labs = colors.map(::rgbToOklab)
    val blobs = ANCHORS.zip(labs.drop(1))
    val twoSigmaSq = 2 * BLOB_SIGMA * BLOB_SIGMA
    val out = List(3) { FloatArray(gridW * gridH) }
    for (gy in 0 until gridH) {
        val y = gy.toDouble() / (gridH - 1)
        for (gx in 0 until gridW) {
            val x = gx.toDouble() / (gridW - 1)
            var total = BASE_WEIGHT
            val acc = DoubleArray(3) { BASE_WEIGHT * labs[0][it] }
            for ((anchor, lab) in blobs) {
                val dx = x - anchor.first
                val dy = y - anchor.second
                val w = exp(-(dx * dx + dy * dy) / twoSigmaSq)
                total += w
                for (i in 0..2) acc[i] += w * lab[i]
            }
            val linear = oklabToLinear(DoubleArray(3) { acc[it] / total })
            for (i in 0..2) out[i][gy * gridW + gx] = toSrgb255(linear[i]).toFloat()
        }
    }
    return out
}

private fun toLinear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

private fun toSrgb255(c: Double): Double {
    val s = if (c <= 0.0031308) 12.92 * c else 1.055 * maxOf(c, 0.0).pow(1 / 2.4) - 0.055
    return (s * 255).coerceIn(0.0, 255.0)
}

internal fun rgbToOklab(rgb: Rgb): DoubleArray {
    val r = toLinear(rgb.r / 255.0)
    val g = toLinear(rgb.g / 255.0)
    val b = toLinear(rgb.b / 255.0)
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return doubleArrayOf(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )
}

private fun oklabToLinear(lab: DoubleArray): DoubleArray {
    val bigL = lab[0]
    val a = lab[1]
    val b = lab[2]
    val l = (bigL + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m = (bigL - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val s = (bigL - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    return doubleArrayOf(
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    )
}

/** Pillow's precompute_coeffs for the bicubic filter (a = -0.5). */
private class Coefficients(inSize: Int, outSize: Int) {
    val starts = IntArray(outSize)
    val weights: Array<DoubleArray>

    init {
        val scale = inSize.toDouble() / outSize
        val filterScale = maxOf(scale, 1.0)
        val support = BICUBIC_SUPPORT * filterScale
        weights = Array(outSize) { xx ->
            val center = (xx + 0.5) * scale
            // (int) casts in C truncate toward zero, as toInt() does.
            val xmin = maxOf((center - support + 0.5).toInt(), 0)
            val xmax = minOf((center + support + 0.5).toInt(), inSize) - xmin
            val k = DoubleArray(xmax) { bicubic((it + xmin - center + 0.5) / filterScale) }
            val sum = k.sum()
            if (sum != 0.0) for (i in k.indices) k[i] /= sum
            starts[xx] = xmin
            k
        }
    }

    private fun bicubic(x0: Double): Double {
        val x = if (x0 < 0) -x0 else x0
        return when {
            x < 1.0 -> ((A + 2.0) * x - (A + 3.0)) * x * x + 1
            x < 2.0 -> (((x - 5) * x + 8) * x - 4) * A
            else -> 0.0
        }
    }

    private companion object {
        const val A = -0.5
        const val BICUBIC_SUPPORT = 2.0
    }
}
