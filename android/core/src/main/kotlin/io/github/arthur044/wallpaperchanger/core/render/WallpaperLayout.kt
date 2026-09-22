package io.github.arthur044.wallpaperchanger.core.render

import io.github.arthur044.wallpaperchanger.core.config.Settings
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

data class TextLayout(
    val titleSizePx: Float,
    val artistSizePx: Float,
    /** Top of the title's line box; the renderer places the baseline from its font metrics. */
    val titleTop: Int,
    val artistTop: Int,
    /** Bottom of the artist's line box: the lowest pixel the text may reach. */
    val bottom: Int,
    val maxWidth: Int,
    val centerX: Int,
)

data class WallpaperLayout(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val art: PixelRect,
    val cornerRadiusPx: Float,
    val shadowBlurPx: Float,
    val shadowSpreadPx: Float,
    val shadowOffsetYPx: Float,
    /** Null when track info is off, or when there's no room for it. */
    val text: TextLayout?,
)

/**
 * Places the art and the track text inside [CanvasSpec.safeArea] so the result
 * looks the same on any screen:
 * - every size derives from the safe area's short side (the desktop version
 *   used the screen height, which overflows on portrait phones);
 * - text never drops below a legible dp size;
 * - art and text are centered together, then nudged by art_offset_y_pct;
 * - the art gives way to the text on short canvases, and the text is dropped
 *   rather than shrinking the art past [MIN_ART_KEPT_WITH_TEXT];
 * - art is never upscaled past [MAX_ART_UPSCALE] of its source, to stay sharp.
 *
 * [sourceArtSidePx] is the source image's side, or null when unknown (no cap).
 */
fun computeLayout(canvas: CanvasSpec, settings: Settings, sourceArtSidePx: Int?): WallpaperLayout {
    val safe = canvas.safeArea
    val short = min(safe.width, safe.height)
    var desiredArt = floor(short * settings.artSizePct).toInt()
    if (sourceArtSidePx != null) desiredArt = min(desiredArt, (sourceArtSidePx * MAX_ART_UPSCALE).toInt())
    desiredArt = desiredArt.coerceAtLeast(1)

    if (settings.showTrackInfo) {
        placeWithText(canvas, settings, short, desiredArt)?.let { return it }
    }
    val top = blockTop(canvas, settings, blockHeight = desiredArt)
    return withArt(canvas, settings, artRect(safe, desiredArt, top), text = null)
}

private fun placeWithText(canvas: CanvasSpec, settings: Settings, short: Int, desiredArt: Int): WallpaperLayout? {
    val safe = canvas.safeArea
    val titleSize = maxOf(short * TITLE_SIZE_OF_SHORT, MIN_TITLE_DP * canvas.density)
    val artistSize = maxOf(short * ARTIST_SIZE_OF_SHORT, MIN_ARTIST_DP * canvas.density)
    // Integer pieces, so the block height is exactly what gets laid out.
    val gapAboveText = (short * GAP_ART_TO_TEXT_OF_SHORT).roundToInt()
    val titleLine = ceil(titleSize * LINE_HEIGHT).toInt()
    val gapBetweenLines = (short * GAP_BETWEEN_LINES_OF_SHORT).roundToInt()
    val artistLine = ceil(artistSize * LINE_HEIGHT).toInt()
    val textHeight = gapAboveText + titleLine + gapBetweenLines + artistLine

    val art = min(desiredArt, safe.height - textHeight)
    if (art < desiredArt * MIN_ART_KEPT_WITH_TEXT) return null

    val top = blockTop(canvas, settings, blockHeight = art + textHeight)
    val artRect = artRect(safe, art, top)
    val titleTop = artRect.bottom + gapAboveText
    val artistTop = titleTop + titleLine + gapBetweenLines
    val text = TextLayout(
        titleSizePx = titleSize,
        artistSizePx = artistSize,
        titleTop = titleTop,
        artistTop = artistTop,
        bottom = artistTop + artistLine,
        maxWidth = floor(safe.width * TEXT_MAX_WIDTH_OF_SAFE).toInt(),
        centerX = safe.centerX,
    )
    return withArt(canvas, settings, artRect, text)
}

// Centers a block of [blockHeight] in the safe area, applies the offset, and
// keeps it inside the safe area.
private fun blockTop(canvas: CanvasSpec, settings: Settings, blockHeight: Int): Int {
    val safe = canvas.safeArea
    val centered = safe.top + (safe.height - blockHeight) / 2
    val offset = (settings.artOffsetYPct * canvas.canvasHeight).roundToInt()
    return (centered + offset).coerceIn(safe.top, (safe.bottom - blockHeight).coerceAtLeast(safe.top))
}

private fun artRect(safe: PixelRect, side: Int, top: Int): PixelRect {
    val left = safe.centerX - side / 2
    return PixelRect(left, top, left + side, top + side)
}

private fun withArt(canvas: CanvasSpec, settings: Settings, art: PixelRect, text: TextLayout?): WallpaperLayout {
    // Settings keep the desktop's pixel values (tuned for 734.4 px of art on a
    // 1080p screen); scaling them by the art keeps the same look everywhere.
    val scale = art.width / DESKTOP_REFERENCE_ART_PX
    val blur = settings.shadowBlurRadius * scale
    return WallpaperLayout(
        canvasWidth = canvas.canvasWidth,
        canvasHeight = canvas.canvasHeight,
        art = art,
        cornerRadiusPx = (settings.cornerRadius * scale).coerceAtMost(art.width / 2f),
        shadowBlurPx = blur,
        shadowSpreadPx = blur / 2,
        shadowOffsetYPx = if (blur > 0f) DESKTOP_SHADOW_OFFSET_PX * scale else 0f,
        text = text,
    )
}

const val MAX_ART_UPSCALE = 2.0
const val MIN_ART_KEPT_WITH_TEXT = 0.75
private const val DESKTOP_REFERENCE_ART_PX = 1080 * 0.68f
private const val DESKTOP_SHADOW_OFFSET_PX = 8f
private const val TITLE_SIZE_OF_SHORT = 0.045f
private const val ARTIST_SIZE_OF_SHORT = 0.032f
private const val MIN_TITLE_DP = 18f
private const val MIN_ARTIST_DP = 13f
private const val GAP_ART_TO_TEXT_OF_SHORT = 0.04f
private const val GAP_BETWEEN_LINES_OF_SHORT = 0.012f
private const val LINE_HEIGHT = 1.25f
private const val TEXT_MAX_WIDTH_OF_SAFE = 0.85f
