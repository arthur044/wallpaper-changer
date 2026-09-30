package io.github.arthur044.wallpaperchanger.core.cache

import io.github.arthur044.wallpaperchanger.core.render.Rgb

/** What was worked out for an album's art so far; either part may still be missing. */
data class StoredColors(val dominant: Rgb? = null, val accents: List<Rgb>? = null) {
    /** One small text file: a version line, then `dominant r,g,b` and `accents r,g,b;r,g,b`. */
    fun encode(): String = buildString {
        appendLine(VERSION)
        dominant?.let { appendLine("dominant ${it.r},${it.g},${it.b}") }
        accents?.let { list -> appendLine("accents " + list.joinToString(";") { "${it.r},${it.g},${it.b}" }) }
    }

    companion object {
        private const val VERSION = "colors-1"

        /** Null for anything that is not a file [encode] wrote: the colors are then worked out again. */
        fun decode(text: String): StoredColors? {
            val lines = text.lines().filter { it.isNotBlank() }
            if (lines.firstOrNull() != VERSION) return null
            var dominant: Rgb? = null
            var accents: List<Rgb>? = null
            for (line in lines.drop(1)) {
                val (key, value) = line.split(' ', limit = 2).takeIf { it.size == 2 } ?: return null
                when (key) {
                    "dominant" -> dominant = parseRgb(value) ?: return null
                    "accents" -> accents = value.split(';').map { parseRgb(it) ?: return null }
                    else -> return null
                }
            }
            return StoredColors(dominant, accents)
        }

        private fun parseRgb(text: String): Rgb? {
            val parts = text.trim().split(',').map { it.toIntOrNull() ?: return null }
            return if (parts.size == 3 && parts.all { it in 0..255 }) Rgb(parts[0], parts[1], parts[2]) else null
        }
    }
}

/**
 * An album's dominant color and accent palette, worked out at most once: from
 * what was stored, else by the callers' [compute] (ColorThief, ~200 ms for the
 * accents on the desktop). [remember] gets the stored form whenever something
 * new was worked out, so the next style change reads it instead.
 *
 * A [compute] that finds nothing (unreadable art) answers null or an empty
 * list, and nothing is remembered for it.
 */
class AlbumColors(
    stored: StoredColors? = null,
    private val remember: (StoredColors) -> Unit = {},
) {
    private var dominant: Rgb? = stored?.dominant
    private var accents: List<Rgb>? = stored?.accents?.takeIf { it.isNotEmpty() }

    fun dominant(compute: () -> Rgb?): Rgb? {
        dominant?.let { return it }
        val found = compute() ?: return null
        dominant = found
        remember(StoredColors(dominant, accents))
        return found
    }

    fun accents(compute: () -> List<Rgb>): List<Rgb> {
        accents?.let { return it }
        val found = compute().takeIf { it.isNotEmpty() } ?: return emptyList()
        accents = found
        remember(StoredColors(dominant, accents))
        return found
    }
}
