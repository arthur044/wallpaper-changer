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

/** Light text on dark backgrounds and vice versa (Rec. 709 luminance, as on desktop). */
fun textColorFor(background: Rgb): Rgb {
    val luminance = 0.2126 * background.r + 0.7152 * background.g + 0.0722 * background.b
    return if (luminance < 140) LIGHT_TEXT else DARK_TEXT
}
