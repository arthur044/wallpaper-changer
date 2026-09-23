package io.github.arthur044.wallpaperchanger.core.cache

import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.render.PixelRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaseCacheKeyTest {
    private val phone = CanvasSpec(1080, 2400, PixelRect(0, 63, 1080, 2274), density = 2.625f)
    private val key = baseCacheKey("1rG6IgNdwE1IGFuIKuYosz", phone, Settings())

    @Test
    fun `is stable for the same inputs`() {
        assertEquals(key, baseCacheKey("1rG6IgNdwE1IGFuIKuYosz", phone, Settings()))
    }

    @Test
    fun `starts with the album id and canvas size for readability`() {
        assertTrue(key.startsWith("1rG6IgNdwE1IGFuIKuYosz_1080x2400_"), key)
    }

    @Test
    fun `differs per album`() {
        assertNotEquals(key, baseCacheKey("otherAlbum", phone, Settings()))
    }

    @Test
    fun `differs when the screen changes`() {
        val rotatedTablet = phone.copy(canvasWidth = 2400, canvasHeight = 2400)
        val otherInsets = phone.copy(safeArea = PixelRect(0, 80, 1080, 2274))
        assertNotEquals(key, baseCacheKey("1rG6IgNdwE1IGFuIKuYosz", rotatedTablet, Settings()))
        assertNotEquals(key, baseCacheKey("1rG6IgNdwE1IGFuIKuYosz", otherInsets, Settings()))
    }

    @Test
    fun `differs when a setting that changes the base changes`() {
        listOf(
            Settings(artSizePct = 0.5),
            Settings(cornerRadius = 4),
            Settings(shadowBlurRadius = 0),
            Settings(artOffsetYPct = -0.1),
            // Toggling the text moves the art, so the base changes too.
            Settings(showTrackInfo = false),
        ).forEach { changed ->
            assertNotEquals(key, baseCacheKey("1rG6IgNdwE1IGFuIKuYosz", phone, changed), "$changed")
        }
    }

    @Test
    fun `ignores settings that never touch the pixels`() {
        val unrelated = Settings(
            clientId = "0123456789abcdef0123456789abcdef",
            webApiPollIntervalSeconds = 60.0,
            syncLockScreen = true,
            useMediaSession = true,
            paused = true,
        )
        assertEquals(key, baseCacheKey("1rG6IgNdwE1IGFuIKuYosz", phone, unrelated))
    }

    @Test
    fun `an unexpected album id cannot escape into the file system`() {
        val odd = baseCacheKey("../../etc/passwd", phone, Settings())
        assertTrue(odd.matches(Regex("[A-Za-z0-9_x]+")), odd)
        assertNotEquals(odd, baseCacheKey("..-..-etc-passwd", phone, Settings()))
    }
}
