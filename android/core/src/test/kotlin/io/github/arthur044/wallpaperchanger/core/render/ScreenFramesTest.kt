package io.github.arthur044.wallpaperchanger.core.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScreenFramesTest {
    // Canvases of a Fold-like phone (see CanvasResizesTest): tall closed, square open.
    private val closed = SizedFrame(904, 2316, "closed")
    private val open = SizedFrame(2176, 2176, "open")

    @Test
    fun `nothing published means nothing to show`() {
        val frames = ScreenFrames.empty<String>()

        assertTrue(frames.isEmpty)
        assertNull(frames.bestFor(904, 2316))
    }

    @Test
    fun `each screen gets the frame drawn for its own shape`() {
        val frames = ScreenFrames.empty<String>().with(closed, "t1").with(open, "t1")

        assertEquals("closed", frames.bestFor(904, 2316))
        assertEquals("open", frames.bestFor(2176, 1812))
        assertEquals("open", frames.bestFor(1812, 2176))
    }

    @Test
    fun `the surface need not match the canvas exactly, only its shape`() {
        // A launcher may ask for a surface wider or taller than the canvas.
        val frames = ScreenFrames.empty<String>().with(closed, "t1").with(open, "t1")

        assertEquals("closed", frames.bestFor(1080, 2400))
    }

    @Test
    fun `a new track drops the frames drawn for the other screen`() {
        // Otherwise opening the phone would show the previous song.
        val frames = ScreenFrames.empty<String>()
            .with(closed, "t1")
            .with(open, "t1")
            .with(SizedFrame(904, 2316, "closed t2"), "t2")

        assertEquals(listOf("closed t2"), frames.frames.map { it.image })
        assertEquals("closed t2", frames.bestFor(2176, 1812), "until redrawn, the only frame there is")
    }

    @Test
    fun `a redraw for the same size replaces the old frame`() {
        val frames = ScreenFrames.empty<String>()
            .with(closed, "t1")
            .with(open, "t1")
            .with(SizedFrame(904, 2316, "closed again"), "t1")

        assertEquals(listOf("closed again", "open"), frames.frames.map { it.image })
    }

    @Test
    fun `at most two shapes are kept, dropping the oldest`() {
        val frames = ScreenFrames.empty<String>()
            .with(closed, "t1")
            .with(open, "t1")
            .with(SizedFrame(1600, 2560, "tablet"), "t1")

        assertEquals(listOf("tablet", "open"), frames.frames.map { it.image })
    }

    @Test
    fun `a frame read from disk is replaced by the first drawing`() {
        val frames = ScreenFrames.of(open).with(closed, "t1")

        assertEquals(listOf("closed"), frames.frames.map { it.image })
    }

    @Test
    fun `without a surface size the newest frame is shown`() {
        val frames = ScreenFrames.empty<String>().with(closed, "t1").with(open, "t1")

        assertEquals("open", frames.bestFor(0, 0))
    }

    @Test
    fun `a frame must have a size`() {
        assertThrows<IllegalArgumentException> { SizedFrame(0, 10, "x") }
    }
}
