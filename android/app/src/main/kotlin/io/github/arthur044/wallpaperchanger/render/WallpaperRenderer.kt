package io.github.arthur044.wallpaperchanger.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import android.text.TextPaint
import android.text.TextUtils
import io.github.arthur044.wallpaperchanger.core.render.ColorThief
import io.github.arthur044.wallpaperchanger.core.render.PixelRect
import io.github.arthur044.wallpaperchanger.core.render.Rgb
import io.github.arthur044.wallpaperchanger.core.render.WallpaperLayout
import io.github.arthur044.wallpaperchanger.core.render.textColorFor
import kotlin.math.min

/** The per-album part of a wallpaper, identical for every track on the album. */
class RenderedBase(val bitmap: Bitmap, val background: Rgb)

/**
 * Draws wallpapers onto a software Canvas (renderer.py's Pillow pipeline):
 * the base (dominant-color fill, blurred shadow, rounded art) is cacheable per
 * album; the final image is a copy of it with the track text on top.
 *
 * CPU-bound: call from a background dispatcher.
 */
class WallpaperRenderer {

    fun decodeArt(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Album art is not a decodable image (${bytes.size} bytes)")

    /** The desktop's background color for this art (color_extractor.py), same algorithm and fallback. */
    fun dominantColor(art: Bitmap): Rgb {
        val pixels = IntArray(art.width * art.height)
        art.getPixels(pixels, 0, art.width, 0, 0, art.width, art.height)
        return ColorThief.dominantColor(pixels) ?: FALLBACK_BACKGROUND
    }

    fun renderBase(art: Bitmap, layout: WallpaperLayout): RenderedBase = drawBase(art, layout, dominantColor(art))

    fun drawBase(art: Bitmap, layout: WallpaperLayout, background: Rgb): RenderedBase {
        val bitmap = createBitmap(layout.canvasWidth, layout.canvasHeight)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background.argb)
        drawShadow(canvas, layout)
        drawArt(canvas, art, layout)
        return RenderedBase(bitmap, background)
    }

    /** A new bitmap: the base plus track text. The base itself is left untouched for reuse. */
    fun drawFinal(base: RenderedBase, layout: WallpaperLayout, trackName: String?, artistName: String?): Bitmap {
        val final = base.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val text = layout.text ?: return final
        val canvas = Canvas(final)
        val color = textColorFor(base.background).argb
        drawLine(canvas, trackName, text.titleSizePx, bold = true, top = text.titleTop, bottom = text.artistTop, text.maxWidth, text.centerX, color)
        drawLine(canvas, artistName, text.artistSizePx, bold = false, top = text.artistTop, bottom = text.bottom, text.maxWidth, text.centerX, color)
        return final
    }

    private fun drawShadow(canvas: Canvas, layout: WallpaperLayout) {
        if (layout.shadowBlurPx <= 0f) return
        val spread = layout.shadowSpreadPx
        val box = layout.art.toRectF().apply {
            inset(-spread, -spread)
            offset(0f, layout.shadowOffsetYPx)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SHADOW_COLOR
            maskFilter = BlurMaskFilter(layout.shadowBlurPx, BlurMaskFilter.Blur.NORMAL)
        }
        val radius = layout.cornerRadiusPx + spread
        canvas.drawRoundRect(box, radius, radius, paint)
    }

    private fun drawArt(canvas: Canvas, art: Bitmap, layout: WallpaperLayout) {
        val rect = layout.art
        val square = centerSquare(art)
        val scaled = square.scale(rect.width, rect.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(rect.left.toFloat(), rect.top.toFloat()) })
            }
        }
        canvas.drawRoundRect(rect.toRectF(), layout.cornerRadiusPx, layout.cornerRadiusPx, paint)
        if (scaled !== square) scaled.recycle()
        if (square !== art) square.recycle()
    }

    // Album art is square, but a non-square image is center-cropped rather than stretched.
    private fun centerSquare(art: Bitmap): Bitmap {
        if (art.width == art.height) return art
        val side = min(art.width, art.height)
        return Bitmap.createBitmap(art, (art.width - side) / 2, (art.height - side) / 2, side, side)
    }

    @Suppress("LongParameterList")
    private fun drawLine(
        canvas: Canvas,
        text: String?,
        sizePx: Float,
        bold: Boolean,
        top: Int,
        bottom: Int,
        maxWidth: Int,
        centerX: Int,
        color: Int,
    ) {
        if (text.isNullOrBlank()) return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizePx
            typeface = if (bold) BOLD else REGULAR
            textAlign = Paint.Align.CENTER
        }
        val shown = TextUtils.ellipsize(text, paint, maxWidth.toFloat(), TextUtils.TruncateAt.END).toString()
        val metrics = paint.fontMetrics
        // Vertically centers the glyphs' full extent inside the line box.
        val baseline = top + ((bottom - top) - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        canvas.drawText(shown, centerX.toFloat(), baseline, paint)
    }

    companion object {
        /** color_extractor.py's _FALLBACK_COLOR, used when the art has no usable pixel. */
        val FALLBACK_BACKGROUND = Rgb(30, 30, 30)
        private val SHADOW_COLOR = Color.argb(140, 0, 0, 0)
        private val BOLD: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        private val REGULAR: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
}

private fun PixelRect.toRectF() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
