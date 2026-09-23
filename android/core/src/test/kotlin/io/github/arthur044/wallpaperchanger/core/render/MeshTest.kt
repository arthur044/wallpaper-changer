package io.github.arthur044.wallpaperchanger.core.render

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

// Expected values below come from the desktop (color_extractor.pick_mesh_colors
// and graphics/mesh.py on Pillow 12.3), so both platforms paint the same mesh.
class MeshTest {
    private val colorful = listOf(Rgb(20, 40, 110), Rgb(240, 150, 30), Rgb(30, 190, 150), Rgb(200, 40, 120))

    private fun IntArray.at(width: Int, x: Int, y: Int) = Rgb.fromArgb(this[y * width + x])

    private fun assertClose(expected: Rgb, actual: Rgb, tolerance: Int = 1, where: String = "") {
        val ok = abs(expected.r - actual.r) <= tolerance &&
            abs(expected.g - actual.g) <= tolerance &&
            abs(expected.b - actual.b) <= tolerance
        assertTrue(ok, "$where expected ~$expected, got $actual")
    }

    private fun means(pixels: IntArray): DoubleArray {
        val sums = DoubleArray(3)
        for (p in pixels) {
            sums[0] += (p shr 16) and 0xFF
            sums[1] += (p shr 8) and 0xFF
            sums[2] += p and 0xFF
        }
        return DoubleArray(3) { sums[it] / pixels.size }
    }

    @Test
    fun `mesh colors match the desktop`() {
        assertEquals(
            listOf(Rgb(30, 40, 90), Rgb(220, 120, 40), Rgb(200, 30, 80), Rgb(40, 180, 160)),
            pickMeshColors(
                Rgb(30, 40, 90),
                listOf(Rgb(30, 40, 90), Rgb(32, 42, 88), Rgb(220, 120, 40), Rgb(200, 30, 80), Rgb(40, 180, 160), Rgb(90, 90, 90)),
            ),
        )
        assertEquals(listOf(Rgb(8, 8, 8), Rgb(52, 52, 52), Rgb(87, 87, 87)), pickMeshColors(Rgb(8, 8, 8), listOf(Rgb(8, 8, 8), Rgb(12, 12, 12))))
        assertEquals(listOf(Rgb(200, 60, 60), Rgb(210, 95, 95), Rgb(120, 36, 36)), pickMeshColors(Rgb(200, 60, 60), emptyList()))
        assertEquals(
            listOf(Rgb(240, 240, 235), Rgb(180, 170, 160), Rgb(144, 144, 141)),
            pickMeshColors(Rgb(240, 240, 235), listOf(Rgb(240, 240, 235), Rgb(180, 170, 160))),
        )
    }

    @Test
    fun `grid matches the desktop's float grid`() {
        val (r, g, b) = meshGrid(64, 36, colorful)
        val expected = mapOf(
            (0 to 0) to floatArrayOf(180.2206f, 126.4371f, 82.189f),
            (63 to 0) to floatArrayOf(30.1431f, 148.6854f, 141.9528f),
            (32 to 18) to floatArrayOf(133.6237f, 113.5643f, 119.7232f),
            (10 to 30) to floatArrayOf(86.4733f, 66.9111f, 112.046f),
            (50 to 5) to floatArrayOf(59.5478f, 156.3613f, 140.8245f),
        )
        expected.forEach { (xy, want) ->
            val i = xy.second * 64 + xy.first
            assertArrayEquals(want, floatArrayOf(r[i], g[i], b[i]), 0.01f, "grid at $xy")
        }
    }

    @Test
    fun `landscape mesh matches the desktop pixel for pixel, within one level`() {
        val pixels = meshPixels(128, 72, colorful)
        mapOf(
            (0 to 0) to Rgb(179, 126, 82), (127 to 0) to Rgb(30, 149, 142),
            (0 to 71) to Rgb(42, 50, 111), (127 to 71) to Rgb(154, 56, 119),
            (64 to 36) to Rgb(133, 114, 119), (5 to 60) to Rgb(69, 66, 110),
            (100 to 20) to Rgb(74, 153, 140), (33 to 47) to Rgb(130, 94, 108),
        ).forEach { (xy, want) -> assertClose(want, pixels.at(128, xy.first, xy.second), where = "$xy") }
        assertArrayEquals(doubleArrayOf(130.724, 109.297, 114.828), means(pixels), 0.5)
    }

    @Test
    fun `portrait mesh matches the desktop too`() {
        val pixels = meshPixels(108, 240, colorful)
        mapOf(
            (0 to 0) to Rgb(180, 126, 82), (107 to 239) to Rgb(154, 56, 120),
            (54 to 120) to Rgb(133, 115, 119), (20 to 200) to Rgb(94, 69, 112),
        ).forEach { (xy, want) -> assertClose(want, pixels.at(108, xy.first, xy.second), where = "$xy") }
        assertArrayEquals(doubleArrayOf(130.951, 109.57, 114.824), means(pixels), 0.5)
    }

    @Test
    fun `dark mesh has no flat bands`() {
        // A near-black cover spans a few 8-bit levels across the screen: rounded
        // without dithering, each level is a plateau hundreds of pixels wide. The
        // eye averages over a few pixels, so judge 8x8 block means along a band.
        val width = 1920
        val pixels = meshPixels(width, 1080, listOf(Rgb(8, 8, 8), Rgb(52, 52, 52), Rgb(87, 87, 87)))
        val blockMeans = (0 until width step 8).map { x0 ->
            var sum = 0
            for (y in 200 until 208) for (x in x0 until x0 + 8) sum += (pixels[y * width + x] shr 16) and 0xFF
            sum
        }
        var longest = 1
        var run = 1
        for (i in 1 until blockMeans.size) {
            run = if (blockMeans[i] == blockMeans[i - 1]) run + 1 else 1
            longest = maxOf(longest, run)
        }
        assertTrue(longest < 8, "8x8 averages stuck on one value for $longest blocks: a visible band")
    }

    @Test
    fun `dithering does not shift the average color`() {
        val pixels = meshPixels(256, 144, listOf(Rgb(40, 40, 40), Rgb(40, 40, 40)))
        means(pixels).forEach { assertEquals(40.0, it, 0.5) }
        pixels.forEach { p -> assertTrue(((p shr 16) and 0xFF) in 39..41) }
    }

    @Test
    fun `one color is a flat fill`() {
        val pixels = meshPixels(64, 36, listOf(Rgb(90, 20, 40)))
        assertTrue(pixels.all { it == Rgb(90, 20, 40).argb })
    }

    @Test
    fun `mesh is deterministic`() {
        assertArrayEquals(meshPixels(160, 90, colorful), meshPixels(160, 90, colorful))
    }
}
