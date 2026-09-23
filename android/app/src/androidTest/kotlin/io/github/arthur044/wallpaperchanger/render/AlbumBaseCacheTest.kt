package io.github.arthur044.wallpaperchanger.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AlbumBaseCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "test_album_bases")
    private var now = 1_000_000L

    private fun cache(maxBytes: Long = 50L * 1024 * 1024) = AlbumBaseCache(dir, maxBytes, clock = { now })

    private fun base(color: Int) = RenderedBase(
        Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(color) },
    )

    @Before
    @After
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun storedBaseComesBackIntact() {
        cache().put("a1_120x240_abc", base(Color.RED), sourceArtSidePx = 640)

        val hit = checkNotNull(cache().get("a1_120x240_abc"))
        assertEquals(120, hit.base.bitmap.width)
        assertEquals(240, hit.base.bitmap.height)
        assertEquals(Color.RED, hit.base.bitmap.getPixel(60, 120))
        assertEquals(640, hit.sourceArtSidePx)
    }

    @Test
    fun unknownKeyMisses() {
        cache().put("a1_120x240_abc", base(Color.RED), 640)
        assertNull(cache().get("a2_120x240_abc"))
    }

    @Test
    fun corruptFileIsDiscardedAsAMiss() {
        dir.mkdirs()
        val broken = File(dir, "a1_120x240_abc.640.png").apply { writeText("not a png") }

        assertNull(cache().get("a1_120x240_abc"))
        assertFalse(broken.exists())
    }

    @Test
    fun storingAgainReplacesTheEntry() {
        cache().put("a1_120x240_abc", base(Color.RED), 640)
        cache().put("a1_120x240_abc", base(Color.BLUE), 300)

        assertEquals(1, dir.listFiles()?.size)
        val hit = checkNotNull(cache().get("a1_120x240_abc"))
        assertEquals(Color.BLUE, hit.base.bitmap.getPixel(0, 0))
        assertEquals(300, hit.sourceArtSidePx)
    }

    @Test
    fun leastRecentlyUsedIsEvictedOverBudget() {
        val oneEntry = run {
            cache().put("probe_120x240_abc", base(Color.RED), 640)
            dir.listFiles().orEmpty().sumOf { it.length() }.also { clean() }
        }
        // Room for two entries, not three.
        val small = cache(maxBytes = oneEntry * 2 + oneEntry / 2)

        small.put("old_120x240_abc", base(Color.RED), 640); now += 1_000
        small.put("mid_120x240_abc", base(Color.GREEN), 640); now += 1_000
        assertNotNull("a hit refreshes recency", small.get("old_120x240_abc")); now += 1_000
        small.put("new_120x240_abc", base(Color.BLUE), 640)

        assertNull("mid was the least recently used", small.get("mid_120x240_abc"))
        assertNotNull(small.get("old_120x240_abc"))
        assertNotNull(small.get("new_120x240_abc"))
    }

    @Test
    fun noTemporaryFilesAreLeftBehind() {
        cache().put("a1_120x240_abc", base(Color.RED), 640)
        assertTrue(dir.listFiles().orEmpty().all { it.name.endsWith(".png") })
    }
}
