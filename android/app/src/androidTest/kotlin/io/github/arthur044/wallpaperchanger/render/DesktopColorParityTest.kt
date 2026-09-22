package io.github.arthur044.wallpaperchanger.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Real album art decoded by Android vs the color the desktop computed for the
 * same file. Needs fixtures the repo doesn't ship (the art is copyrighted), so
 * it skips unless they were pushed into the app's cache:
 *
 *   cache/color_parity/<name>.jpg   the art
 *   cache/color_parity/expected.txt lines "<name> <rrggbb>" from color_extractor.py
 *
 * Decoders differ slightly (libjpeg in Pillow vs Skia), so a small tolerance.
 */
@RunWith(AndroidJUnit4::class)
class DesktopColorParityTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "color_parity")

    @Test
    fun androidPicksTheDesktopBackgroundForRealArt() {
        val expected = File(dir, "expected.txt")
        assumeTrue("no parity fixtures pushed", expected.exists())

        val renderer = WallpaperRenderer()
        val report = expected.readLines().filter { it.isNotBlank() }.map { line ->
            val (name, hex) = line.trim().split(" ")
            val art = renderer.decodeArt(File(dir, "$name.jpg").readBytes())
            val got = renderer.dominantColor(art)
            val want = hex.toInt(16)
            val diff = maxOf(
                abs(got.r - (want shr 16 and 0xFF)),
                abs(got.g - (want shr 8 and 0xFF)),
                abs(got.b - (want and 0xFF)),
            )
            Triple(name, "%02x%02x%02x".format(got.r, got.g, got.b) + " vs " + hex, diff)
        }
        report.forEach { (name, text, diff) -> println("parity $name $text diff=$diff") }
        assertTrue(report.joinToString("\n"), report.all { it.third <= TOLERANCE })
    }

    private companion object {
        const val TOLERANCE = 3
    }
}
