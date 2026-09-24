package io.github.arthur044.wallpaperchanger.core.render

import kotlin.math.abs
import kotlin.math.ln

/** One drawing of the wallpaper, for a canvas of this size. */
data class SizedFrame<T>(val width: Int, val height: Int, val image: T) {
    init {
        require(width > 0 && height > 0) { "a frame must have a size, was ${width}x$height" }
    }
}

/**
 * The same wallpaper drawn for each screen shape seen so far: a foldable has
 * a tall canvas when closed and a square one when open (see [canvasSpec]).
 * Keeping both lets the live wallpaper switch at once when the phone is
 * folded back, instead of stretching the other screen's drawing.
 *
 * Every frame shows the same [content] (track and look). A frame of new
 * content drops the others: they show what used to play.
 */
class ScreenFrames<T> private constructor(
    val content: Any?,
    /** Newest first. */
    val frames: List<SizedFrame<T>>,
) {
    val newest: T? get() = frames.firstOrNull()?.image

    val isEmpty: Boolean get() = frames.isEmpty()

    /** A copy with [frame] as the newest; the other sizes survive only if [content] is the same. */
    fun with(frame: SizedFrame<T>, content: Any?): ScreenFrames<T> {
        val kept = if (content == this.content) {
            frames.filterNot { it.width == frame.width && it.height == frame.height }
        } else {
            emptyList()
        }
        return ScreenFrames(content, (listOf(frame) + kept).take(MAX_SIZES))
    }

    /**
     * The frame whose shape is closest to a [width] x [height] surface; the
     * newest wins a tie. Null only when there is no frame at all.
     */
    fun bestFor(width: Int, height: Int): T? {
        if (width <= 0 || height <= 0) return newest
        val wanted = aspect(width, height)
        return frames.minByOrNull { abs(aspect(it.width, it.height) - wanted) }?.image
    }

    override fun equals(other: Any?): Boolean =
        other is ScreenFrames<*> && other.content == content && other.frames == frames

    override fun hashCode(): Int = 31 * (content?.hashCode() ?: 0) + frames.hashCode()

    override fun toString(): String = "ScreenFrames(${frames.map { "${it.width}x${it.height}" }})"

    companion object {
        // Folded and unfolded. A third shape (another display) replaces the oldest.
        private const val MAX_SIZES = 2

        fun <T> empty(): ScreenFrames<T> = ScreenFrames(null, emptyList())

        /** A single frame of unknown content, such as one read back from disk. */
        fun <T> of(frame: SizedFrame<T>): ScreenFrames<T> = ScreenFrames(null, listOf(frame))

        // Log of the ratio: 2:1 and 1:2 are equally far from a square.
        private fun aspect(width: Int, height: Int): Double = ln(width.toDouble() / height)
    }
}
