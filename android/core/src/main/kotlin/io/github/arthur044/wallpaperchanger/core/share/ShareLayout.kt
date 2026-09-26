package io.github.arthur044.wallpaperchanger.core.share

import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.render.PixelRect
import io.github.arthur044.wallpaperchanger.core.render.TextLayout
import io.github.arthur044.wallpaperchanger.core.render.WallpaperLayout
import io.github.arthur044.wallpaperchanger.core.render.computeLayout
import io.github.arthur044.wallpaperchanger.core.render.framedArt
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * The lyrics share image (Android only; no desktop counterpart): the
 * wallpaper's own base, with a glass card over the art holding a header
 * (thumbnail, track, artist) and the chosen verses, the wallpaper's track
 * text below, cropped to 9:16 around that block.
 *
 * Card text is sized by the card, not the screen density: the image is seen
 * on other people's phones, so the wallpaper's dp floors don't apply. Its
 * floors are fractions of the image width instead. Proportions are those of
 * the approved mock (card 818 px: padding 59, thumbnail 104, verses 64 px).
 */

private const val PAD_OF_CARD = 0.072f
private const val THUMB_OF_CARD = 0.127f
private const val THUMB_RADIUS_OF_THUMB = 0.08f
private const val THUMB_TO_TEXT_OF_CARD = 0.032f
private const val HEADER_TITLE_OF_CARD = 0.037f
private const val HEADER_ARTIST_OF_CARD = 0.033f
private const val HEADER_TO_VERSES_OF_CARD = 0.08f
private const val VERSE_MAX_OF_CARD = 0.078f
private const val VERSE_MIN_OF_CARD = 0.05f
private const val HEADER_TITLE_MIN_OF_WIDTH = 0.022f
private const val HEADER_ARTIST_MIN_OF_WIDTH = 0.019f
private const val VERSE_MIN_OF_WIDTH = 0.028f
private const val HEADER_LINE_HEIGHT = 1.25f
private const val VERSE_LINE_HEIGHT = 1.35f
private const val FIT_STEP_PX = 1f

/** Instagram Stories' frame. */
private const val SHARE_ASPECT = 16.0 / 9.0

data class ShareHeader(
    val titleSizePx: Float,
    val artistSizePx: Float,
    /** Tops of the line boxes; the renderer places baselines from its font metrics. */
    val titleTop: Int,
    val artistTop: Int,
    /** Bottom of the artist's line box. */
    val bottom: Int,
    val textLeft: Int,
    val maxWidth: Int,
)

/** Where the verses go, and the size range they may be drawn at. */
data class ShareVerses(val area: PixelRect, val maxSizePx: Float, val minSizePx: Float) {
    fun lineHeightPx(sizePx: Float): Int = ceil(sizePx * VERSE_LINE_HEIGHT).toInt()

    /** Lines that fit at the smallest size: the most a selection may wrap to. */
    val maxLines: Int get() = area.height / lineHeightPx(minSizePx)

    fun fits(lines: Int, sizePx: Float): Boolean = lines * lineHeightPx(sizePx) <= area.height

    /**
     * The largest size, from [maxSizePx] down to [minSizePx], at which the
     * verses fit; null when they don't even at the smallest. [linesAt] is how
     * many lines the verses wrap to at a size (measured by the renderer).
     */
    fun fit(linesAt: (Float) -> Int): Float? {
        var size = maxSizePx
        while (true) {
            if (fits(linesAt(size), size)) return size
            if (size <= minSizePx) return null
            size = maxOf(minSizePx, size - FIT_STEP_PX)
        }
    }
}

data class ShareLayout(
    /** The wallpaper layout the base was drawn with; its text is the bottom text. */
    val wallpaper: WallpaperLayout,
    /** Part of the base canvas that becomes the image. */
    val crop: PixelRect,
    /** Rows the crop must keep: the art box (frame included) down to the bottom text. */
    val block: PixelRect,
    /** The glass card: exactly the drawn art, inside the frame. */
    val card: PixelRect,
    val cardRadiusPx: Float,
    val thumb: PixelRect,
    val thumbRadiusPx: Float,
    val header: ShareHeader,
    val verses: ShareVerses,
) {
    val bottomText: TextLayout? get() = wallpaper.text
}

/**
 * The share image for a base drawn for [canvas] and [settings] from art of
 * [sourceArtSidePx] (the cached base's own value, so the card lands exactly on
 * the art that base shows).
 */
fun shareLayout(canvas: CanvasSpec, settings: Settings, sourceArtSidePx: Int): ShareLayout {
    val wallpaper = computeLayout(canvas, settings, sourceArtSidePx)
    val card = framedArt(wallpaper.art, settings.artFrame)
    val side = card.width.toFloat()
    val width = canvas.canvasWidth

    val pad = (side * PAD_OF_CARD).roundToInt()
    val thumbSide = (side * THUMB_OF_CARD).roundToInt()
    val titleSize = maxOf(side * HEADER_TITLE_OF_CARD, width * HEADER_TITLE_MIN_OF_WIDTH)
    val artistSize = maxOf(side * HEADER_ARTIST_OF_CARD, width * HEADER_ARTIST_MIN_OF_WIDTH)
    val titleLine = ceil(titleSize * HEADER_LINE_HEIGHT).toInt()
    val artistLine = ceil(artistSize * HEADER_LINE_HEIGHT).toInt()
    // The thumbnail and the two header lines share a row, centered on each other.
    val rowTop = card.top + pad
    val row = maxOf(thumbSide, titleLine + artistLine)
    val thumbTop = rowTop + (row - thumbSide) / 2
    val thumb = PixelRect(card.left + pad, thumbTop, card.left + pad + thumbSide, thumbTop + thumbSide)
    val textLeft = thumb.right + (side * THUMB_TO_TEXT_OF_CARD).roundToInt()
    val titleTop = rowTop + (row - titleLine - artistLine) / 2
    val header = ShareHeader(
        titleSizePx = titleSize,
        artistSizePx = artistSize,
        titleTop = titleTop,
        artistTop = titleTop + titleLine,
        bottom = titleTop + titleLine + artistLine,
        textLeft = textLeft,
        maxWidth = (card.right - pad - textLeft).coerceAtLeast(0),
    )

    val versesTop = rowTop + row + (side * HEADER_TO_VERSES_OF_CARD).roundToInt()
    val area = PixelRect(card.left + pad, versesTop, card.right - pad, (card.bottom - pad).coerceAtLeast(versesTop))
    val minVerse = maxOf(side * VERSE_MIN_OF_CARD, width * VERSE_MIN_OF_WIDTH)
    val verses = ShareVerses(area, maxSizePx = maxOf(side * VERSE_MAX_OF_CARD, minVerse), minSizePx = minVerse)

    val block = PixelRect(0, wallpaper.art.top, width, wallpaper.text?.bottom ?: wallpaper.art.bottom)
    return ShareLayout(
        wallpaper = wallpaper,
        crop = crop(canvas, block),
        block = block,
        card = card,
        cardRadiusPx = wallpaper.cornerRadiusPx,
        thumb = thumb,
        thumbRadiusPx = thumbSide * THUMB_RADIUS_OF_THUMB,
        header = header,
        verses = verses,
    )
}

/**
 * 9:16 around [block] (not around the screen's center, so art_offset_y never
 * cuts the card) when the canvas is taller than that; otherwise the whole
 * canvas: a 9:16 phone as is, a square canvas (tablet, unfolded foldable) square.
 */
private fun crop(canvas: CanvasSpec, block: PixelRect): PixelRect {
    val width = canvas.canvasWidth
    val height = canvas.canvasHeight
    val cropHeight = (width * SHARE_ASPECT).roundToInt()
    if (height <= cropHeight) return PixelRect(0, 0, width, height)
    val top = (block.top - (cropHeight - block.height) / 2).coerceIn(0, height - cropHeight)
    return PixelRect(0, top, width, top + cropHeight)
}

/** The selected lines as drawn: a stanza break at either end would only be empty space. */
fun versesToDraw(selected: List<String>): List<String> =
    selected.dropWhile(String::isBlank).dropLastWhile(String::isBlank)
