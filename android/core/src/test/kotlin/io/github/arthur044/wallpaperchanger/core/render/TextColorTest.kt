package io.github.arthur044.wallpaperchanger.core.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Same rule as renderer._text_color_for_background.
class TextColorTest {
    @Test
    fun `dark backgrounds get light text`() {
        assertEquals(Rgb(245, 245, 245), textColorFor(Rgb(0, 0, 0)))
        assertEquals(Rgb(245, 245, 245), textColorFor(Rgb(30, 30, 30)))
    }

    @Test
    fun `light backgrounds get dark text`() {
        assertEquals(Rgb(20, 20, 20), textColorFor(Rgb(255, 255, 255)))
    }

    @Test
    fun `luminance weights green far above blue`() {
        // Pure green is bright (luminance ~182), pure blue is dark (~18).
        assertEquals(Rgb(20, 20, 20), textColorFor(Rgb(0, 255, 0)))
        assertEquals(Rgb(245, 245, 245), textColorFor(Rgb(0, 0, 255)))
    }

    @Test
    fun `argb packing is opaque`() {
        assertEquals(0xFF102030.toInt(), Rgb(0x10, 0x20, 0x30).argb)
        assertEquals(Rgb(0x10, 0x20, 0x30), Rgb.fromArgb(0xFF102030.toInt()))
    }
}
