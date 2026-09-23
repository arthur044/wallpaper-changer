package io.github.arthur044.wallpaperchanger.core.render

data class Rgb(val r: Int, val g: Int, val b: Int) {
    /** Opaque ARGB int, as android.graphics.Color uses. */
    val argb: Int get() = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    companion object {
        fun fromArgb(color: Int) = Rgb((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
    }
}

val LIGHT_TEXT = Rgb(245, 245, 245)
val DARK_TEXT = Rgb(20, 20, 20)

/** Mean of opaque ARGB pixels, each channel rounded to the nearest level. */
fun averageColor(argbPixels: IntArray): Rgb {
    require(argbPixels.isNotEmpty()) { "no pixels to average" }
    var r = 0L
    var g = 0L
    var b = 0L
    for (p in argbPixels) {
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
    }
    val n = argbPixels.size
    return Rgb(((r + n / 2) / n).toInt(), ((g + n / 2) / n).toInt(), ((b + n / 2) / n).toInt())
}

/** Light text on dark backgrounds and vice versa (Rec. 709 luminance, as on desktop). */
fun textColorFor(background: Rgb): Rgb {
    val luminance = 0.2126 * background.r + 0.7152 * background.g + 0.0722 * background.b
    return if (luminance < 140) LIGHT_TEXT else DARK_TEXT
}
