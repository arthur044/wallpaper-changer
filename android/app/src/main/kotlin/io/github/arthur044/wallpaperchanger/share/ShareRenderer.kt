package io.github.arthur044.wallpaperchanger.share

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.scale
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.config.TextCard
import io.github.arthur044.wallpaperchanger.core.render.PixelRect
import io.github.arthur044.wallpaperchanger.core.render.Rgb
import io.github.arthur044.wallpaperchanger.core.render.averageColor
import io.github.arthur044.wallpaperchanger.core.render.frostPixels
import io.github.arthur044.wallpaperchanger.core.render.textColorFor
import io.github.arthur044.wallpaperchanger.core.share.ShareLayout
import io.github.arthur044.wallpaperchanger.core.share.ShareVerses
import io.github.arthur044.wallpaperchanger.render.CachedBase
import io.github.arthur044.wallpaperchanger.render.WallpaperRenderer
import io.github.arthur044.wallpaperchanger.render.baselineCenteredIn

/**
 * Draws the lyrics share image on a copy of the wallpaper's base: the
 * wallpaper's own bottom text (drawFinal), a dark glass card over the art,
 * a thumbnail cut from the base's own art, the header and the verses, then
 * the 9:16 crop. CPU-bound: call from a background dispatcher.
 */
class ShareRenderer(private val renderer: WallpaperRenderer) {

    /** Whether [verses] fit the card at some size; the screen limits the selection with it. */
    fun versesFit(layout: ShareLayout, verses: List<String>): Boolean = fitSize(layout.verses, verses) != null

    /**
     * A new bitmap, the crop of the base with everything drawn. [base] is left
     * untouched (the caller recycles it).
     *
     * @throws IllegalArgumentException if [verses] is empty or doesn't fit.
     */
    fun draw(base: CachedBase, layout: ShareLayout, nowPlaying: NowPlaying, verses: List<String>, textCard: TextCard): Bitmap {
        require(verses.isNotEmpty()) { "No verses to share" }
        val verseSize = requireNotNull(fitSize(layout.verses, verses)) { "${verses.size} verses don't fit the card" }

        val full = renderer.drawFinal(base.base, layout.wallpaper, nowPlaying.trackName, nowPlaying.artistName, textCard)
        try {
            val canvas = Canvas(full)
            // Cut before the glass covers it: the base's own art, no download.
            val thumb = cutThumbnail(base.base.bitmap, layout)
            drawDarkGlass(full, canvas, layout.card, layout.cardRadiusPx)
            val color = textColorFor(averageColorIn(full, layout.card)).argb
            drawRounded(canvas, thumb, layout.thumb, layout.thumbRadiusPx)
            thumb.recycle()
            drawHeader(canvas, layout, nowPlaying, color)
            drawVerses(canvas, layout.verses, verses, verseSize, color)
            return cropOf(full, layout.crop)
        } catch (e: Throwable) {
            full.recycle()
            throw e
        }
    }

    private fun fitSize(verses: ShareVerses, lines: List<String>): Float? {
        if (lines.isEmpty()) return null
        val text = lines.joinToString("\n")
        return verses.fit { size -> breakLines(text, verses.area.width, size).lineCount }
    }

    private fun breakLines(text: String, width: Int, sizePx: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, versePaint(sizePx), width).setIncludePad(false).build()

    private fun versePaint(sizePx: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx
        typeface = BOLD
    }

    private fun cutThumbnail(base: Bitmap, layout: ShareLayout): Bitmap {
        val card = layout.card
        val art = Bitmap.createBitmap(base, card.left, card.top, card.width, card.height)
        val thumb = art.scale(layout.thumb.width, layout.thumb.height)
        if (thumb !== art) art.recycle()
        return thumb
    }

    // The card region blurred on a quarter-size copy (like the other blurs),
    // tinted, darkened so white verses read on any art, clipped and edged.
    private fun drawDarkGlass(bitmap: Bitmap, canvas: Canvas, card: PixelRect, radius: Float) {
        val region = Bitmap.createBitmap(bitmap, card.left, card.top, card.width, card.height)
        val small = region.scale(
            (card.width / GLASS_DOWNSCALE).coerceAtLeast(1),
            (card.height / GLASS_DOWNSCALE).coerceAtLeast(1),
        )
        if (small !== region) region.recycle()
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val sigma = card.width * GLASS_BLUR_OF_CARD / GLASS_DOWNSCALE
        small.setPixels(frostPixels(pixels, small.width, small.height, sigma), 0, small.width, 0, 0, small.width, small.height)
        val glass = small.scale(card.width, card.height)
        if (glass !== small) small.recycle()

        val rect = card.toRectF()
        canvas.drawRoundRect(rect, radius, radius, shaderPaint(glass, card))
        val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(GLASS_SCRIM_ALPHA, 0, 0, 0) }
        canvas.drawRoundRect(rect, radius, radius, scrim)
        val edgeWidth = maxOf(1f, card.width / GLASS_EDGE_DIVISOR)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = edgeWidth
            color = Color.argb(GLASS_EDGE_ALPHA, 255, 255, 255)
        }
        rect.inset(edgeWidth / 2, edgeWidth / 2)
        canvas.drawRoundRect(rect, radius, radius, edge)
        glass.recycle()
    }

    private fun drawRounded(canvas: Canvas, image: Bitmap, rect: PixelRect, radius: Float) {
        canvas.drawRoundRect(rect.toRectF(), radius, radius, shaderPaint(image, rect))
    }

    private fun shaderPaint(image: Bitmap, at: PixelRect) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setTranslate(at.left.toFloat(), at.top.toFloat()) })
        }
    }

    private fun drawHeader(canvas: Canvas, layout: ShareLayout, nowPlaying: NowPlaying, color: Int) {
        val header = layout.header
        headerLine(canvas, nowPlaying.trackName, header.titleSizePx, BOLD, color, header.titleTop, header.artistTop, layout)
        val artistColor = Color.argb(SECONDARY_TEXT_ALPHA, Color.red(color), Color.green(color), Color.blue(color))
        headerLine(canvas, nowPlaying.artistName, header.artistSizePx, REGULAR, artistColor, header.artistTop, header.bottom, layout)
    }

    @Suppress("LongParameterList")
    private fun headerLine(
        canvas: Canvas,
        text: String?,
        sizePx: Float,
        typeface: Typeface,
        color: Int,
        top: Int,
        bottom: Int,
        layout: ShareLayout,
    ) {
        if (text.isNullOrBlank()) return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.typeface = typeface
            this.color = color
        }
        val shown = TextUtils.ellipsize(text, paint, layout.header.maxWidth.toFloat(), TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, layout.header.textLeft.toFloat(), paint.baselineCenteredIn(top, bottom), paint)
    }

    // One line box per wrapped line, of exactly the height the layout counted.
    private fun drawVerses(canvas: Canvas, verses: ShareVerses, lines: List<String>, sizePx: Float, color: Int) {
        val text = lines.joinToString("\n")
        val broken = breakLines(text, verses.area.width, sizePx)
        val paint = versePaint(sizePx).apply { this.color = color }
        val lineHeight = verses.lineHeightPx(sizePx)
        for (i in 0 until broken.lineCount) {
            val line = text.substring(broken.getLineStart(i), broken.getLineEnd(i)).trimEnd()
            if (line.isEmpty()) continue
            val top = verses.area.top + i * lineHeight
            canvas.drawText(line, verses.area.left.toFloat(), paint.baselineCenteredIn(top, top + lineHeight), paint)
        }
    }

    private fun averageColorIn(bitmap: Bitmap, area: PixelRect): Rgb {
        val pixels = IntArray(area.width * area.height)
        bitmap.getPixels(pixels, 0, area.width, area.left, area.top, area.width, area.height)
        return averageColor(pixels)
    }

    private fun cropOf(full: Bitmap, crop: PixelRect): Bitmap {
        if (crop.left == 0 && crop.top == 0 && crop.width == full.width && crop.height == full.height) return full
        val cropped = Bitmap.createBitmap(full, crop.left, crop.top, crop.width, crop.height)
        if (cropped !== full) full.recycle()
        return cropped
    }

    private companion object {
        const val GLASS_DOWNSCALE = 4
        const val GLASS_BLUR_OF_CARD = 0.05f
        const val GLASS_SCRIM_ALPHA = 107 // 42 % black
        const val GLASS_EDGE_ALPHA = 70
        const val GLASS_EDGE_DIVISOR = 400f
        const val SECONDARY_TEXT_ALPHA = 217 // 85 %
        val BOLD: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val REGULAR: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
}

private fun PixelRect.toRectF() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
