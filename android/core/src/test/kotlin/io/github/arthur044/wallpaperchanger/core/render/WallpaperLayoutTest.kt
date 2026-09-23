package io.github.arthur044.wallpaperchanger.core.render

import io.github.arthur044.wallpaperchanger.core.config.Settings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.math.abs
import kotlin.math.min

class WallpaperLayoutTest {

    // --- invariants that must hold on every screen shape -------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    fun `art is a non-empty square`(profile: Profile) {
        val art = layoutFor(profile).art
        assertTrue(art.width > 0, "art width ${art.width}")
        assertEquals(art.width, art.height)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    fun `art stays inside the safe area`(profile: Profile) {
        assertContains(profile.canvas.safeArea, layoutFor(profile).art)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    fun `art is horizontally centered in the safe area`(profile: Profile) {
        val layout = layoutFor(profile)
        assertTrue(abs(layout.art.centerX - profile.canvas.safeArea.centerX) <= 1)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    fun `text block stays inside the safe area`(profile: Profile) {
        val layout = layoutFor(profile)
        val text = layout.text ?: return
        val safe = profile.canvas.safeArea
        assertTrue(text.titleTop >= layout.art.bottom, "text overlaps the art")
        assertTrue(text.bottom <= safe.bottom, "text bottom ${text.bottom} > ${safe.bottom}")
        assertTrue(text.maxWidth <= safe.width)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    fun `art never exceeds the configured share of the short side`(profile: Profile) {
        val safe = profile.canvas.safeArea
        val limit = min(safe.width, safe.height) * Settings().artSizePct
        assertTrue(layoutFor(profile).art.width <= limit + 1)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    fun `text stays legible at the screen's density`(profile: Profile) {
        val text = layoutFor(profile).text ?: return
        val density = profile.canvas.density
        assertTrue(text.titleSizePx >= MIN_TITLE_DP * density - 0.01f, "title ${text.titleSizePx}px")
        assertTrue(text.artistSizePx >= MIN_ARTIST_DP * density - 0.01f, "artist ${text.artistSizePx}px")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    fun `rounded corners never exceed half the art`(profile: Profile) {
        val layout = layoutFor(profile)
        assertTrue(layout.cornerRadiusPx <= layout.art.width / 2f)
    }

    // --- specific behavior ------------------------------------------------

    @Test
    fun `on a 20 by 9 phone the art takes its share of the width`() {
        val layout = layoutFor(PHONE_FHD)
        assertEquals((1080 * 0.68).toInt(), layout.art.width)
    }

    @Test
    fun `art plus text is vertically centered in the safe area`() {
        val layout = layoutFor(PHONE_FHD)
        val safe = PHONE_FHD.canvas.safeArea
        val text = checkNotNull(layout.text) { "expected track text in the layout" }
        val spaceAbove = layout.art.top - safe.top
        val spaceBelow = safe.bottom - text.bottom
        assertTrue(abs(spaceAbove - spaceBelow) <= 2, "above=$spaceAbove below=$spaceBelow")
    }

    @Test
    fun `without track info the art alone is vertically centered`() {
        val layout = computeLayout(PHONE_FHD.canvas, Settings(showTrackInfo = false), SOURCE_ART)
        val safe = PHONE_FHD.canvas.safeArea
        assertNull(layout.text)
        assertTrue(abs(layout.art.centerY - safe.centerY) <= 1)
    }

    @Test
    fun `on a short canvas the art gives way so art and text fit the height`() {
        // 90% of 1080 = 972 px of art plus ~160 px of text would overflow 1080.
        val landscape = CanvasSpec(2400, 1080, PixelRect(0, 0, 2400, 1080), density = 2.625f)
        val layout = computeLayout(landscape, Settings(artSizePct = 0.9), SOURCE_ART)
        val text = checkNotNull(layout.text) { "expected track text in the layout" }
        assertTrue(layout.art.width < 972, "art ${layout.art.width} should give way to the text")
        assertTrue(text.bottom <= 1080, "text bottom ${text.bottom}")
    }

    @Test
    fun `small source art is not blown up past twice its size`() {
        val layout = computeLayout(PHONE_FHD.canvas, Settings(), sourceArtSidePx = 300)
        assertEquals(600, layout.art.width)
    }

    @Test
    fun `unknown source size means no upscale cap`() {
        assertEquals((1080 * 0.68).toInt(), computeLayout(PHONE_FHD.canvas, Settings(), sourceArtSidePx = null).art.width)
    }

    @Test
    fun `vertical offset moves the block and is clamped to the safe area`() {
        val centered = layoutFor(PHONE_FHD)
        val raised = computeLayout(PHONE_FHD.canvas, Settings(artOffsetYPct = -0.05), SOURCE_ART)
        assertEquals(centered.art.top - (0.05 * 2400).toInt(), raised.art.top, absTolerance = 1)

        val pinned = computeLayout(PHONE_FHD.canvas, Settings(artOffsetYPct = -0.4), SOURCE_ART)
        assertEquals(PHONE_FHD.canvas.safeArea.top, pinned.art.top)
    }

    @Test
    fun `corner radius and shadow scale with the art, keeping the desktop look`() {
        // Settings are desktop pixels for a 1080p screen, where the art was 734.4 px.
        val full = computeLayout(PHONE_FHD.canvas, Settings(cornerRadius = 16, shadowBlurRadius = 24), SOURCE_ART)
        val half = computeLayout(PHONE_FHD.canvas, Settings(artSizePct = 0.34, cornerRadius = 16, shadowBlurRadius = 24), SOURCE_ART)
        assertEquals(16f * full.art.width / 734.4f, full.cornerRadiusPx, 0.01f)
        assertEquals(full.cornerRadiusPx / 2, half.cornerRadiusPx, 0.1f)
        assertEquals(full.shadowBlurPx / 2, half.shadowBlurPx, 0.1f)
    }

    @Test
    fun `a zero shadow setting produces no shadow`() {
        assertEquals(0f, computeLayout(PHONE_FHD.canvas, Settings(shadowBlurRadius = 0), SOURCE_ART).shadowBlurPx)
    }

    @Test
    fun `text is dropped when keeping it would shrink the art too much`() {
        val tiny = CanvasSpec(400, 260, PixelRect(0, 0, 400, 260), density = 3f)
        val layout = computeLayout(tiny, Settings(), SOURCE_ART)
        assertNull(layout.text)
        assertTrue(layout.art.width > 0)
    }

    // --- helpers -----------------------------------------------------------

    data class Profile(val name: String, val canvas: CanvasSpec) {
        override fun toString() = name
    }

    private fun layoutFor(profile: Profile) = computeLayout(profile.canvas, Settings(), SOURCE_ART)

    private fun assertContains(outer: PixelRect, inner: PixelRect) {
        assertTrue(
            inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom,
            "$inner not inside $outer",
        )
    }

    private fun assertEquals(expected: Int, actual: Int, absTolerance: Int) {
        assertTrue(abs(expected - actual) <= absTolerance, "expected $expected ±$absTolerance, was $actual")
    }

    companion object {
        private const val SOURCE_ART = 640 // Spotify's largest album image
        private const val MIN_TITLE_DP = 18f
        private const val MIN_ARTIST_DP = 13f

        // Galaxy A71-class: 1080x2400 @ 2.625, status 24dp, 3-button nav 48dp.
        private val PHONE_FHD = Profile(
            "phone 1080x2400",
            CanvasSpec(1080, 2400, PixelRect(0, 63, 1080, 2400 - 126), density = 2.625f),
        )

        @JvmStatic
        fun profiles() = listOf(
            PHONE_FHD,
            Profile("phone QHD 1440x3200", CanvasSpec(1440, 3200, PixelRect(0, 84, 1440, 3200 - 168), density = 3.5f)),
            Profile("compact 720x1600", CanvasSpec(720, 1600, PixelRect(0, 48, 720, 1600 - 96), density = 2f)),
            Profile("old 16:9 720x1280", CanvasSpec(720, 1280, PixelRect(0, 48, 720, 1280 - 96), density = 2f)),
            Profile("landscape canvas 2400x1080", CanvasSpec(2400, 1080, PixelRect(126, 63, 2400 - 126, 1080), density = 2.625f)),
            // Tablets/foldables: square bitmap, content kept in the centered
            // square that stays visible in both orientations.
            Profile("tablet 2560x1600", CanvasSpec(2560, 2560, PixelRect(480 + 60, 480 + 60, 2080 - 60, 2080 - 60), density = 2f)),
            Profile("foldable inner 2208x1840", CanvasSpec(2208, 2208, PixelRect(184 + 70, 184 + 70, 2024 - 70, 2024 - 70), density = 2.625f)),
            Profile("tiny safe area 400x260", CanvasSpec(400, 260, PixelRect(0, 0, 400, 260), density = 3f)),
        )
    }
}

class TextBandTest {
    @Test
    fun `band spans the text lines at the max width, centered`() {
        val text = TextLayout(
            titleSizePx = 40f, artistSizePx = 28f,
            titleTop = 1500, artistTop = 1560, bottom = 1600,
            maxWidth = 900, centerX = 540,
        )
        assertEquals(PixelRect(90, 1500, 990, 1600), text.band)
    }
}
