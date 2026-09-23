package io.github.arthur044.wallpaperchanger.core.render

import io.github.arthur044.wallpaperchanger.core.config.ArtFrame
import kotlin.math.roundToInt

/** One glass rim: its gap around the (shrunken) art, as a fraction of the art side, and its alphas. */
data class FrameRim(val gapOfArt: Float, val veilAlpha: Int, val edgeAlpha: Int)

/** The desktop's renderer._FRAME_RIMS, outermost first. */
fun frameRims(frame: ArtFrame): List<FrameRim> = when (frame) {
    ArtFrame.NONE -> emptyList()
    ArtFrame.SINGLE -> listOf(FrameRim(0.05f, 28, 110))
    ArtFrame.DOUBLE -> listOf(FrameRim(0.075f, 24, 90), FrameRim(0.035f, 28, 110))
}

/**
 * The art shrunk so that it plus its outermost rim fill [art], the box the
 * layout gave the art: shadow, glow and text don't move with a frame on.
 */
fun framedArt(art: PixelRect, frame: ArtFrame): PixelRect {
    val outer = frameRims(frame).firstOrNull() ?: return art
    val inner = (art.width / (1 + 2 * outer.gapOfArt)).roundToInt()
    val offset = (art.width - inner) / 2
    return PixelRect(art.left + offset, art.top + offset, art.left + offset + inner, art.top + offset + inner)
}
