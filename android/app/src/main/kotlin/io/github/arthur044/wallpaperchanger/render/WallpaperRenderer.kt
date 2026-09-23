package io.github.arthur044.wallpaperchanger.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import android.text.TextPaint
import android.text.TextUtils
import io.github.arthur044.wallpaperchanger.core.config.ArtFrame
import io.github.arthur044.wallpaperchanger.core.config.BackgroundStyle
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.config.TextCard
import io.github.arthur044.wallpaperchanger.core.render.GLASS_EDGE_ALPHA
import io.github.arthur044.wallpaperchanger.core.render.GlassCard
import io.github.arthur044.wallpaperchanger.core.render.ACCENT_COLOR_COUNT
import io.github.arthur044.wallpaperchanger.core.render.ACCENT_QUALITY
import io.github.arthur044.wallpaperchanger.core.render.ColorThief
import io.github.arthur044.wallpaperchanger.core.render.PixelRect
import io.github.arthur044.wallpaperchanger.core.render.Rgb
import io.github.arthur044.wallpaperchanger.core.render.TextLayout
import io.github.arthur044.wallpaperchanger.core.render.WallpaperLayout
import io.github.arthur044.wallpaperchanger.core.render.averageColor
import io.github.arthur044.wallpaperchanger.core.render.blurredArtBackground
import io.github.arthur044.wallpaperchanger.core.render.frameRims
import io.github.arthur044.wallpaperchanger.core.render.framedArt
import io.github.arthur044.wallpaperchanger.core.render.frostPixels
import io.github.arthur044.wallpaperchanger.core.render.glassCard
import io.github.arthur044.wallpaperchanger.core.render.meshPixels
import io.github.arthur044.wallpaperchanger.core.render.needsAccentPalette
import io.github.arthur044.wallpaperchanger.core.render.pickGlowColor
import io.github.arthur044.wallpaperchanger.core.render.pickMeshColors
import io.github.arthur044.wallpaperchanger.core.render.textColorFor
import kotlin.math.ceil
import kotlin.math.floor
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
        val blurredArt = settings.backgroundStyle == BackgroundStyle.BLUR
        return drawBase(art, layout, background, glow, mesh, blurredArt, settings.artFrame)
    }

    /**
     * [glow] replaces the dark drop shadow with a halo of that color; [mesh]
     * replaces the flat [background] fill with a gradient of those colors, and
     * [blurredArt] with the art itself, blurred. A [frame] puts glass rims
     * where the art was and shrinks the art inside them.
     */
    @Suppress("LongParameterList")
    fun drawBase(
        art: Bitmap,
        layout: WallpaperLayout,
        background: Rgb,
        glow: Rgb? = null,
        mesh: List<Rgb>? = null,
        blurredArt: Boolean = false,
        frame: ArtFrame = ArtFrame.NONE,
    ): RenderedBase {
        val (w, h) = layout.canvasWidth to layout.canvasHeight
        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)
        when {
            mesh != null -> bitmap.setPixels(meshPixels(w, h, mesh), 0, w, 0, 0, w, h)
            blurredArt -> bitmap.setPixels(blurredArtBackground(pixelsOf(art), art.width, art.height, w, h), 0, w, 0, 0, w, h)
            else -> canvas.drawColor(background.argb)
        }
        // Shadow and glow keep the art's laid-out box: with a frame, that is the outer rim.
        if (glow != null) drawGlow(canvas, layout, glow) else drawShadow(canvas, layout)
        val artRect = framedArt(layout.art, frame)
        drawFrame(canvas, artRect, frame, layout.cornerRadiusPx, frameEdgeWidth(w, h))
        drawArt(canvas, art, artRect, layout.cornerRadiusPx)
        return RenderedBase(bitmap)
    }

    // A light veil and a hairline edge per rim, outermost first.
    private fun drawFrame(canvas: Canvas, art: PixelRect, frame: ArtFrame, cornerRadius: Float, edgeWidth: Float) {
        for (rim in frameRims(frame)) {
            val gap = Math.rint((art.width * rim.gapOfArt).toDouble()).toFloat()
            val rect = art.toRectF().apply { inset(-gap, -gap) }
            val radius = cornerRadius + gap
            val veil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(rim.veilAlpha, 255, 255, 255) }
            canvas.drawRoundRect(rect, radius, radius, veil)
            rect.inset(edgeWidth / 2, edgeWidth / 2)
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = edgeWidth
                color = Color.argb(rim.edgeAlpha, 255, 255, 255)
            }
            canvas.drawRoundRect(rect, radius, radius, edge)
        }
    }

    // The desktop's round(height / 540) at 1080p, taken from the short side here.
    private fun frameEdgeWidth(width: Int, height: Int): Float =
        maxOf(1f, Math.rint(min(width, height) / FRAME_EDGE_DIVISOR).toFloat())

    private fun pixelsOf(art: Bitmap): IntArray {
        val pixels = IntArray(art.width * art.height)
        art.getPixels(pixels, 0, art.width, 0, 0, art.width, art.height)
        return pixels
    }

    /** A new bitmap: the base plus track text. The base itself is left untouched for reuse. */
    fun drawFinal(
        base: RenderedBase,
        layout: WallpaperLayout,
        trackName: String?,
        artistName: String?,
        textCard: TextCard = TextCard.NONE,
    ): Bitmap {
        val final = base.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val text = layout.text ?: return final
        val lines = textLines(text, trackName, artistName)
        if (lines.isEmpty()) return final

        val canvas = Canvas(final)
        val behindText = if (textCard == TextCard.GLASS) {
            val card = glassCard(inkBounds(lines, text), text.titleSizePx, final.width, final.height)
            drawGlass(final, canvas, card)
            card.box
        } else {
            text.band
        }
        // Sampled from what is actually behind the text: a gradient or a glow
        // has no single color, and a card changes what the text sits on.
        val color = textColorFor(averageColorIn(final, behindText)).argb
        for (line in lines) {
            line.paint.color = color
            canvas.drawText(line.text, text.centerX.toFloat(), line.baseline, line.paint)
        }
        return final
    }

    /** Where the glyphs of this track's text land; null when there is no text. For tests. */
    internal fun inkBounds(layout: WallpaperLayout, trackName: String?, artistName: String?): PixelRect? {
        val text = layout.text ?: return null
        return textLines(text, trackName, artistName).takeIf { it.isNotEmpty() }?.let { inkBounds(it, text) }
    }

    /** The glass card this track's text would get; null when there is no text. For tests. */
    internal fun glassCardFor(layout: WallpaperLayout, trackName: String?, artistName: String?): GlassCard? {
        val text = layout.text ?: return null
        val ink = inkBounds(layout, trackName, artistName) ?: return null
        return glassCard(ink, text.titleSizePx, layout.canvasWidth, layout.canvasHeight)
    }

    private class TextLine(val text: String, val paint: TextPaint, val baseline: Float)

    // Title and artist, ellipsized to the max width, each centered in its line box.
    private fun textLines(text: TextLayout, trackName: String?, artistName: String?): List<TextLine> = listOfNotNull(
        textLine(trackName, text.titleSizePx, bold = true, top = text.titleTop, bottom = text.artistTop, text.maxWidth),
        textLine(artistName, text.artistSizePx, bold = false, top = text.artistTop, bottom = text.bottom, text.maxWidth),
    )

    @Suppress("LongParameterList")
    private fun textLine(text: String?, sizePx: Float, bold: Boolean, top: Int, bottom: Int, maxWidth: Int): TextLine? {
        if (text.isNullOrBlank()) return null
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            typeface = if (bold) BOLD else REGULAR
            textAlign = Paint.Align.CENTER
        }
        val shown = TextUtils.ellipsize(text, paint, maxWidth.toFloat(), TextUtils.TruncateAt.END).toString()
        val metrics = paint.fontMetrics
        // Vertically centers the glyphs' full extent inside the line box.
        val baseline = top + ((bottom - top) - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        return TextLine(shown, paint, baseline)
    }

    // The glyphs' tight box: centered width from measureText (getTextBounds
    // ignores textAlign), height from the glyph bounds around each baseline.
    private fun inkBounds(lines: List<TextLine>, text: TextLayout): PixelRect {
        val glyphs = Rect()
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (line in lines) {
            val half = line.paint.measureText(line.text) / 2
            line.paint.getTextBounds(line.text, 0, line.text.length, glyphs)
            left = minOf(left, floor(text.centerX - half).toInt())
            right = maxOf(right, ceil(text.centerX + half).toInt())
            top = minOf(top, floor(line.baseline + glyphs.top).toInt())
            bottom = maxOf(bottom, ceil(line.baseline + glyphs.bottom).toInt())
        }
        return PixelRect(left, top, right, bottom)
    }

    // Frosted card: the pixels around it blurred (with a margin, so its edge
    // averages the real surroundings), tinted, clipped to a rounded rect, edged.
    private fun drawGlass(bitmap: Bitmap, canvas: Canvas, card: GlassCard) {
        val box = card.box
        if (box.width <= 0 || box.height <= 0) return
        val margin = ceil(card.blurSigmaPx * 2).toInt()
        val outer = PixelRect(
            (box.left - margin).coerceAtLeast(0),
            (box.top - margin).coerceAtLeast(0),
            (box.right + margin).coerceAtMost(bitmap.width),
            (box.bottom + margin).coerceAtMost(bitmap.height),
        )
        val pixels = IntArray(outer.width * outer.height)
        bitmap.getPixels(pixels, 0, outer.width, outer.left, outer.top, outer.width, outer.height)
        val frosted = frostPixels(pixels, outer.width, outer.height, card.blurSigmaPx)

        val glass = createBitmap(box.width, box.height)
        val offset = (box.top - outer.top) * outer.width + (box.left - outer.left)
        glass.setPixels(frosted, offset, outer.width, 0, 0, box.width, box.height)

        val rect = box.toRectF()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(box.left.toFloat(), box.top.toFloat()) })
            }
        }
        canvas.drawRoundRect(rect, card.radiusPx, card.radiusPx, fill)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.argb(GLASS_EDGE_ALPHA, 255, 255, 255)
        }
        rect.inset(0.5f, 0.5f)
        canvas.drawRoundRect(rect, card.radiusPx, card.radiusPx, edge)
        glass.recycle()
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

    private fun drawArt(canvas: Canvas, art: Bitmap, rect: PixelRect, cornerRadius: Float) {
        val square = centerSquare(art)
        val scaled = square.scale(rect.width, rect.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(rect.left.toFloat(), rect.top.toFloat()) })
            }
        }
        canvas.drawRoundRect(rect.toRectF(), cornerRadius, cornerRadius, paint)
        if (scaled !== square) scaled.recycle()
        if (square !== art) square.recycle()
    }

    // Album art is square, but a non-square image is center-cropped rather than stretched.
    private fun centerSquare(art: Bitmap): Bitmap {
        if (art.width == art.height) return art
        val side = min(art.width, art.height)
        return Bitmap.createBitmap(art, (art.width - side) / 2, (art.height - side) / 2, side, side)
    }

    companion object {
        /** color_extractor.py's _FALLBACK_COLOR, used when the art has no usable pixel. */
        val FALLBACK_BACKGROUND = Rgb(30, 30, 30)
        private val SHADOW_COLOR = Color.argb(140, 0, 0, 0)

        // renderer.py's glow: alpha 200, blur 8% and spread 3% of the art side.
        private const val GLOW_ALPHA = 200
        private const val GLOW_BLUR_OF_ART = 0.08f
        private const val GLOW_SPREAD_OF_ART = 0.03f
        private const val FRAME_EDGE_DIVISOR = 540.0
        private val BOLD: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        private val REGULAR: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
}

private fun PixelRect.toRectF() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
