package io.github.arthur044.wallpaperchanger.wallpaper

import kotlin.math.roundToInt

/** How long one wallpaper takes to fade into the next. */
internal const val CROSSFADE_MS = 300L

/**
 * Opacity (0..255) of the incoming image [elapsedMs] into a fade of
 * [durationMs]: eased in and out (smoothstep), 255 once the fade is over.
 */
internal fun crossfadeAlpha(elapsedMs: Long, durationMs: Long = CROSSFADE_MS): Int {
    if (durationMs <= 0 || elapsedMs >= durationMs) return OPAQUE
    if (elapsedMs <= 0) return 0
    val t = elapsedMs.toFloat() / durationMs
    val eased = t * t * (3 - 2 * t)
    return (eased * OPAQUE).roundToInt().coerceIn(0, OPAQUE)
}

private const val OPAQUE = 255
