package io.github.arthur044.wallpaperchanger.core.render

import io.github.arthur044.wallpaperchanger.core.config.BackgroundStyle
import io.github.arthur044.wallpaperchanger.core.config.Settings
import kotlin.math.sqrt

// Ports of color_extractor.py's accent helpers. Same constants and the same
// arithmetic (Python's round() is half-to-even, hence Math.rint), so the same
// art gets the same glow on both platforms.

/** Accent palette: more colors than the dominant's 5, at a coarser sampling. */
const val ACCENT_COLOR_COUNT = 10
const val ACCENT_QUALITY = 10

private const val MIN_GLOW_DISTANCE = 80.0
private const val LIFT_STEP = 0.15
private const val LIGHT_FILL_LUMINANCE = 140.0
private val WHITE = Rgb(255, 255, 255)
private val BLACK = Rgb(0, 0, 0)

/**
 * Quantizing the palette is the costly part of an album's first render, so
 * it is only done for the effects that use the art's accents.
 */
fun needsAccentPalette(settings: Settings): Boolean =
    settings.artGlow || settings.backgroundStyle == BackgroundStyle.MESH

/**
 * The art's most vivid color, pushed away from the fill until it reads
 * against it: toward white on a dark fill, toward black on a light one.
 */
fun pickGlowColor(palette: List<Rgb>, background: Rgb): Rgb {
    // maxByOrNull keeps the first of equals: ties go to the more populous color.
    val color = palette.maxByOrNull(::vividness) ?: background
    val target = if (luminance(background) < LIGHT_FILL_LUMINANCE) WHITE else BLACK
    var t = 0.0
    var lifted = color
    while (distance(lifted, background) < MIN_GLOW_DISTANCE && t < 1.0) {
        t = minOf(1.0, t + LIFT_STEP)
        lifted = mix(color, target, t)
    }
    return lifted
}

// HSV saturation x value, which reduces to (max - min) / 255.
private fun vividness(c: Rgb): Int = maxOf(c.r, c.g, c.b) - minOf(c.r, c.g, c.b)

private fun luminance(c: Rgb): Double = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b

private fun distance(a: Rgb, b: Rgb): Double {
    val dr = (a.r - b.r).toDouble()
    val dg = (a.g - b.g).toDouble()
    val db = (a.b - b.b).toDouble()
    return sqrt(dr * dr + dg * dg + db * db)
}

internal fun mix(a: Rgb, b: Rgb, t: Double): Rgb = Rgb(
    Math.rint(a.r + (b.r - a.r) * t).toInt(),
    Math.rint(a.g + (b.g - a.g) * t).toInt(),
    Math.rint(a.b + (b.b - a.b) * t).toInt(),
)
