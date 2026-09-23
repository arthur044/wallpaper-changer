package io.github.arthur044.wallpaperchanger.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.arthur044.wallpaperchanger.core.config.BackgroundStyle
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.render.PixelRect
import io.github.arthur044.wallpaperchanger.core.render.computeLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * What each style costs on this device, per album (base) and per track
 * (final). Logged under the "RenderCost" tag; the bounds only catch a
 * regression by an order of magnitude, since timings vary between phones.
 */
@RunWith(AndroidJUnit4::class)
class RenderCostTest {
    private val renderer = WallpaperRenderer()
    private val phone = CanvasSpec(1080, 2400, PixelRect(0, 63, 1080, 2274), density = 2.625f)

    // Busy 640 px art, like a real Spotify cover: many colors, soft edges.
    private val art = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(Color.rgb(20, 40, 110))
        val random = Random(4)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(60) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            canvas.drawCircle(random.nextFloat() * 640, random.nextFloat() * 640, 20f + random.nextFloat() * 100, paint)
        }
    }

    private fun millis(runs: Int = 3, block: () -> Unit): Long {
        block() // warm-up
        return (1..runs).minOf {
            val start = SystemClock.elapsedRealtime()
            block()
            SystemClock.elapsedRealtime() - start
        }
    }

    @Test
    fun costPerStyle() {
        val styles = mapOf(
            "solid" to Settings(),
            "glow" to Settings(artGlow = true),
            "mesh" to Settings(backgroundStyle = BackgroundStyle.MESH),
            "mesh+glow" to Settings(backgroundStyle = BackgroundStyle.MESH, artGlow = true),
        )
        styles.forEach { (name, settings) ->
            val layout = computeLayout(phone, settings, sourceArtSidePx = 640)
            val base = millis { renderer.renderBase(art, layout, settings).bitmap.recycle() }
            val built = renderer.renderBase(art, layout, settings)
            val track = millis { renderer.drawFinal(built, layout, "Nome da Faixa Bem Longo", "Artista").recycle() }
            Log.i(TAG, "$name: base $base ms, per track $track ms")
            assertTrue("$name base took $base ms", base < 3_000)
            assertTrue("$name track took $track ms", track < 1_000)
        }
    }

    private companion object {
        const val TAG = "RenderCost"
    }
}
