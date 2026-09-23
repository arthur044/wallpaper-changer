package io.github.arthur044.wallpaperchanger.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import io.github.arthur044.wallpaperchanger.WallpaperApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The app's own live wallpaper: shows the image the sync drew, fading from
 * one to the next. A static wallpaper can't do that: the system drops the old
 * image before it has the new one, so every change blinks black.
 *
 * Draws only when something changes (a new image, the surface, visibility):
 * nothing animates while the home screen just sits there.
 */
class LiveWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = FadingEngine()

    private inner class FadingEngine : Engine() {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()
        private var shown: Bitmap? = null
        private var fadingOut: Bitmap? = null
        private var fadeStart = 0L
        private var width = 0
        private var height = 0
        private var visible = false
        private val nextFrame = Choreographer.FrameCallback { draw() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val frames = (application as WallpaperApp).container.liveFrames
            scope.launch {
                withContext(Dispatchers.IO) { frames.current() }?.let { if (shown == null) show(it, fade = false) }
                frames.latest.filterNotNull().collect { if (it !== shown) show(it, fade = shown != null && visible) }
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.width = width
            this.height = height
            draw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                draw()
            } else {
                // Hidden mid-fade: skip to the end rather than finish it unseen.
                fadingOut = null
                Choreographer.getInstance().removeFrameCallback(nextFrame)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            Choreographer.getInstance().removeFrameCallback(nextFrame)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            scope.cancel()
            Choreographer.getInstance().removeFrameCallback(nextFrame)
            super.onDestroy()
        }

        private fun show(image: Bitmap, fade: Boolean) {
            fadingOut = if (fade) shown else null
            shown = image
            fadeStart = SystemClock.uptimeMillis()
            draw()
        }

        private fun draw() {
            if (!visible || width == 0 || height == 0) return
            val image = shown
            val old = fadingOut
            val alpha = if (old == null) 255 else crossfadeAlpha(SystemClock.uptimeMillis() - fadeStart)
            val holder = surfaceHolder
            val canvas = lockCanvas(holder) ?: return
            try {
                canvas.drawColor(Color.BLACK)
                if (old != null && alpha < 255) drawFilling(canvas, old, 255)
                if (image != null) drawFilling(canvas, image, alpha)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            if (old != null && alpha < 255) {
                Choreographer.getInstance().postFrameCallback(nextFrame)
            } else {
                fadingOut = null
            }
        }

        // Hardware canvases draw the fade on the GPU; fall back where the
        // wallpaper surface refuses one.
        private fun lockCanvas(holder: SurfaceHolder): Canvas? =
            runCatching { holder.lockHardwareCanvas() }.getOrNull()
                ?: runCatching { holder.lockCanvas() }.onFailure { Log.w(TAG, "No canvas to draw on", it) }.getOrNull()

        // The image is drawn for this screen already; center-crop covers any
        // surface that differs (a launcher asking for a wider one, rotation).
        private fun drawFilling(canvas: Canvas, image: Bitmap, alpha: Int) {
            val scale = max(width.toFloat() / image.width, height.toFloat() / image.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate((width - image.width * scale) / 2f, (height - image.height * scale) / 2f)
            paint.alpha = alpha
            canvas.drawBitmap(image, matrix, paint)
        }
    }

    private companion object {
        const val TAG = "LiveWallpaperService"
    }
}
