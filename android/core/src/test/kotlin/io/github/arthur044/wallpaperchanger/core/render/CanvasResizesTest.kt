package io.github.arthur044.wallpaperchanger.core.render

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanvasResizesTest {
    // A Fold-like phone: the cover screen is phone-sized, the inner one tablet-sized.
    // Sizes are illustrative, not measured on a real device.
    private val closed = canvasSpec(ScreenMetrics(904, 2316, 2.625f, 344, Insets(0, 70, 0, 126)))
    private val open = canvasSpec(ScreenMetrics(1812, 2176, 2.625f, 690, Insets(0, 70, 0, 126)))
    private val closedLandscape = canvasSpec(ScreenMetrics(2316, 904, 2.625f, 344, Insets(80, 70, 126, 0)))

    @Test
    fun `the screen as it was is not a change`() = runTest {
        assertEquals(emptyList<CanvasSpec>(), flowOf(closed).resizes().toList())
    }

    @Test
    fun `opening and closing a foldable each call for a new drawing`() = runTest {
        assertEquals(listOf(open, closed), flowOf(closed, open, closed).resizes().toList())
    }

    @Test
    fun `the same size reported again is not a change`() = runTest {
        // Configuration changes also fire for dark mode, font scale, locale...
        assertEquals(listOf(open), flowOf(closed, closed, open, open).resizes().toList())
    }

    @Test
    fun `rotating a phone is not a change`() = runTest {
        // The safe area may move with the bars, but the portrait image stays right.
        assertEquals(emptyList<CanvasSpec>(), flowOf(closed, closedLandscape, closed).resizes().toList())
    }

    @Test
    fun `a foldable's two screens get different canvases`() {
        assertEquals(904 to 2316, closed.canvasWidth to closed.canvasHeight)
        assertEquals(2176 to 2176, open.canvasWidth to open.canvasHeight)
    }
}
