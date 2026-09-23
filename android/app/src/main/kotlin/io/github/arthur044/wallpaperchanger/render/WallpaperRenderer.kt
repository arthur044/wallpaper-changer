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
import io.github.arthur044.wallpaperchanger.core.config.BackgroundStyle
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.ACCENT_COLOR_COUNT
import io.github.arthur044.wallpaperchanger.core.render.ACCENT_QUALITY
import io.github.arthur044.wallpaperchanger.core.render.ColorThief
import io.github.arthur044.wallpaperchanger.core.render.PixelRect
import io.github.arthur044.wallpaperchanger.core.render.Rgb
import io.github.arthur044.wallpaperchanger.core.render.WallpaperLayout
import io.github.arthur044.wallpaperchanger.core.render.averageColor
import io.github.arthur044.wallpaperchanger.core.render.meshPixels
import io.github.arthur044.wallpaperchanger.core.render.needsAccentPalette
import io.github.arthur044.wallpaperchanger.core.render.pickGlowColor
import io.github.arthur044.wallpaperchanger.core.render.pickMeshColors
import io.github.arthur044.wallpaperchanger.core.render.textColorFor
import kotlin.math.min

/** The per-album part of a wallpaper, identical for every track on the album. */
class RenderedBase(val bitmap: Bitmap)

/**
 * Draws wallpapers onto a software Canvas (renderer.py's Pillow pipeline):
 * the base (dominant-color fill, blurred shadow, rounded art) is cacheable per
 * album; the final image is a copy of it with the track text on top, its
 * color picked from the base pixels under the text.
 *
 * CPU-bound: call from a background dispatcher.
 */
class WallpaperRenderer {

    fun decodeArt(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Album art is not a decodable image (${bytes.size} bytes)")

    /** The desktop's background color for this art (color_extractor.py), same algorithm and fallback. */
    fun dominantColor(art: Bitmap): Rgb = dominantColor(pixelsOf(art))

    private fun dominantColor(pixels: IntArray): Rgb = ColorThief.dominantColor(pixels) ?: FALLBACK_BACKGROUND

    /** The art's base with the effects [settings] ask for. */
    fun renderBase(art: Bitmap, layout: WallpaperLayout, settings: Settings): RenderedBase {
        val pixels = pixelsOf(art)
        val background = dominantColor(pixels)
        // A second quantization, so only when an effect needs the accents.
        val palette = if (needsAccentPalette(settings)) {
            ColorThief.palette(pixels, ACCENT_COLOR_COUNT, ACCENT_QUALITY).orEmpty()
        } else {
            emptyList()
        }
        val glow = if (settings.artGlow) pickGlowColor(palette, background) else null
        val mesh = if (settings.backgroundStyle == BackgroundStyle.MESH) pickMeshColors(background, palette) else null
        return drawBase(art, layout, background, glow, mesh)
    }

    /**
     * [glow] replaces the dark drop shadow with a halo of that color; [mesh]
     * replaces the flat [background] fill with a gradient of those colors.
     */
    fun drawBase(
        art: Bitmap,
        layout: WallpaperLayout,
        background: Rgb,
        glow: Rgb? = null,
        mesh: List<Rgb>? = null,
    ): RenderedBase {
        val bitmap = createBitmap(layout.canvasWidth, layout.canvasHeight)
        val canvas = Canvas(bitmap)
        if (mesh != null) {
            val (w, h) = layout.canvasWidth to layout.canvasHeight
            bitmap.setPixels(meshPixels(w, h, mesh), 0, w, 0, 0, w, h)
        } else {
            canvas.drawColor(background.argb)
        }
        if (glow != null) drawGlow(canvas, layout, glow) else drawShadow(canvas, layout)
        drawArt(canvas, art, layout)
        return RenderedBase(bitmap)
    }

    private fun pixelsOf(art: Bitmap): IntArray {
        val pixels = IntArray(art.width * art.height)
        art.getPixels(pixels, 0, art.width, 0, 0, art.width, art.height)
        return pixels
    }

    /** A new bitmap: the base plus track text. The base itself is left untouched for reuse. */
    fun drawFinal(base: RenderedBase, layout: WallpaperLayout, trackName: String?, artistName: String?): Bitmap {
        val final = base.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val text = layout.text ?: return final
        val canvas = Canvas(final)
        // Sampled from what is actually behind the text: a gradient or a glow
        // has no single color, and the fill says nothing about the text band.
        val color = textColorFor(averageColorIn(base.bitmap, text.band)).argb
        drawLine(canvas, trackName, text.titleSizePx, bold = true, top = text.titleTop, bottom = text.artistTop, text.maxWidth, text.centerX, color)
        drawLine(canvas, artistName, text.artistSizePx, bold = false, top = text.artistTop, bottom = text.bottom, text.maxWidth, text.centerX, color)
        return final
    }

    private fun averageColorIn(bitmap: Bitmap, area: PixelRect): Rgb {
        val left = area.left.coerceIn(0, bitmap.width - 1)
        val top = area.top.coerceIn(0, bitmap.height - 1)
        val width = (area.right.coerceAtMost(bitmap.width) - left).coerceAtLeast(1)
        val height = (area.bottom.coerceAtMost(bitmap.height) - top).coerceAtLeast(1)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)
        return averageColor(pixels)
    }

    private fun drawShadow(canvas: Canvas, layout: WallpaperLayout) {
        if (layout.shadowBlurPx <= 0f) return
        drawHalo(canvas, layout, SHADOW_COLOR, layout.shadowBlurPx, layout.shadowSpreadPx, layout.shadowOffsetYPx)
    }

    // The art as a light source: wider than the shadow and centered on it.
    private fun drawGlow(canvas: Canvas, layout: WallpaperLayout, color: Rgb) {
        val side = layout.art.width
        val blur = maxOf(layout.shadowBlurPx * 2, side * GLOW_BLUR_OF_ART)
        val argb = Color.argb(GLOW_ALPHA, color.r, color.g, color.b)
        drawHalo(canvas, layout, argb, blur, side * GLOW_SPREAD_OF_ART, offsetY = 0f)
    }

    // A blurred rounded rectangle behind the art.
    @Suppress("LongParameterList")
    private fun drawHalo(canvas: Canvas, layout: WallpaperLayout, argb: Int, blur: Float, spread: Float, offsetY: Float) {
        val box = layout.art.toRectF().apply {
            inset(-spread, -spread)
            offset(0f, offsetY)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
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

        // renderer.py's glow: alpha 200, blur 8% and spread 3% of the art side.
        private const val GLOW_ALPHA = 200
        private const val GLOW_BLUR_OF_ART = 0.08f
        private const val GLOW_SPREAD_OF_ART = 0.03f
        private val BOLD: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        private val REGULAR: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
}

private fun PixelRect.toRectF() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
