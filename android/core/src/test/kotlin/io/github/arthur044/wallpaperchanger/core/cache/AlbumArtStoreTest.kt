package io.github.arthur044.wallpaperchanger.core.cache

import io.github.arthur044.wallpaperchanger.core.render.Rgb
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AlbumArtStoreTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private fun store(maxBytes: Long = 10_000) = AlbumArtStore(dir, maxBytes, clock = { now })
    private val cover = ByteArray(100) { it.toByte() }

    @Test
    fun `art put once is read back, also by a new run of the app`() {
        store().put("a1", cover)

        assertArrayEquals(cover, store().get("a1"))
        assertNull(store().get("a2"))
    }

    @Test
    fun `colors are kept as they are worked out and read back after a restart`() {
        store().put("a1", cover)
        val colors = store().colors("a1")
        colors.dominant { Rgb(200, 28, 60) }
        colors.accents { listOf(Rgb(1, 2, 3), Rgb(4, 5, 6)) }

        val again = store().colors("a1")

        assertEquals(Rgb(200, 28, 60), again.dominant { error("stored: no ColorThief pass") })
        assertEquals(listOf(Rgb(1, 2, 3), Rgb(4, 5, 6)), again.accents { error("stored: no ColorThief pass") })
    }

    @Test
    fun `the accents wait until a look asks for them, and the dominant stays`() {
        store().put("a1", cover)
        store().colors("a1").dominant { Rgb(9, 9, 9) }

        val later = store().colors("a1")
        assertEquals(Rgb(9, 9, 9), later.dominant { error("stored") })
        assertEquals(listOf(Rgb(1, 2, 3)), later.accents { listOf(Rgb(1, 2, 3)) })

        val stored = store().colors("a1")
        assertEquals(Rgb(9, 9, 9), stored.dominant { error("stored") })
        assertEquals(listOf(Rgb(1, 2, 3)), stored.accents { error("stored") })
    }

    @Test
    fun `art that gave no colors leaves nothing behind`() {
        store().put("a1", cover)
        val colors = store().colors("a1")

        assertNull(colors.dominant { null })
        assertEquals(emptyList<Rgb>(), colors.accents { emptyList() })

        assertFalse(File(dir, "a1.colors").exists())
    }

    @Test
    fun `unusable stored colors are worked out again`() {
        store().put("a1", cover)
        val file = File(dir, "a1.colors")
        listOf("nonsense", "colors-9\ndominant 1,2,3", "colors-1\ndominant 1,2", "colors-1\ndominant 1,2,300", "colors-1\nfoo 1").forEach { text ->
            file.writeText(text)
            assertEquals(Rgb(7, 7, 7), store().colors("a1").dominant { Rgb(7, 7, 7) }, text)
        }
    }

    @Test
    fun `new art for an album drops the colors of the old one`() {
        store().put("a1", cover)
        store().colors("a1").dominant { Rgb(1, 1, 1) }

        store().put("a1", ByteArray(10))

        assertFalse(File(dir, "a1.colors").exists())
    }

    @Test
    fun `discard forgets the art and its colors`() {
        store().put("a1", cover)
        store().colors("a1").dominant { Rgb(1, 1, 1) }

        store().discard("a1")

        assertNull(store().get("a1"))
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
    }

    @Test
    fun `an id that is not a file name cannot leave the folder`() {
        store().put("../../evil", cover)

        assertTrue(dir.listFiles().orEmpty().all { it.parentFile == dir })
        assertFalse(File(dir.parentFile, "evil.art").exists())
        assertArrayEquals(cover, store().get("../../evil"))
    }

    @Test
    fun `the least recently used album goes first and the one just written stays`() {
        val small = ByteArray(40)
        store(maxBytes = 100).apply {
            now = 1_000; put("a1", small)
            now = 2_000; put("a2", small)
            now = 3_000; get("a1") // a2 is now the oldest
            now = 4_000; put("a3", small)
        }

        assertEquals(setOf("a1.art", "a3.art"), dir.list().orEmpty().toSet())
    }

    @Test
    fun `the colors of the album just written are never evicted`() {
        val big = ByteArray(80)
        val s = store(maxBytes = 100)
        now = 1_000; s.put("a1", big)
        now = 2_000; s.put("a2", big)
        s.colors("a2").dominant { Rgb(5, 5, 5) }

        assertTrue(File(dir, "a2.art").exists())
        assertTrue(File(dir, "a2.colors").exists())
    }
}
