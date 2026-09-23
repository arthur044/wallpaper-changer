package io.github.arthur044.wallpaperchanger.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class SettingsTest {
    @Test
    fun `defaults match the desktop settings_py`() {
        val s = Settings()
        assertEquals("", s.clientId)
        assertEquals(25.seconds, s.webApiPollInterval)
        assertEquals(0.68, s.artSizePct)
        assertEquals(16, s.cornerRadius)
        assertEquals(24, s.shadowBlurRadius)
        assertTrue(s.showTrackInfo)
        assertFalse(s.syncLockScreen)
    }

    @Test
    fun `android-only defaults are off or neutral`() {
        val s = Settings()
        assertFalse(s.paused)
        // Opt-in: needs notification access, which onboarding treats as optional.
        assertFalse(s.useMediaSession)
        assertEquals(0.0, s.artOffsetYPct)
    }

    @Test
    fun `background styles default to the original look`() {
        val s = Settings()
        assertEquals(BackgroundStyle.SOLID, s.backgroundStyle)
        assertFalse(s.artGlow)
        assertEquals(TextCard.NONE, s.textCard)
        assertFalse(s.smoothTransition)
    }

    @Test
    fun `sanitized leaves valid values untouched`() {
        val s = Settings(clientId = "abc", artSizePct = 0.5, cornerRadius = 8)
        assertEquals(s, s.sanitized())
    }

    @Test
    fun `sanitized clamps out-of-range values field by field`() {
        val s = Settings(
            webApiPollIntervalSeconds = 1.0,
            artSizePct = 5.0,
            cornerRadius = -3,
            shadowBlurRadius = 10_000,
            artOffsetYPct = -2.0,
        ).sanitized()

        // Below 10s the Web API poll starts tripping Spotify's rate limit.
        assertEquals(10.seconds, s.webApiPollInterval)
        assertEquals(Settings.ART_SIZE_PCT_RANGE.endInclusive, s.artSizePct)
        assertEquals(0, s.cornerRadius)
        assertEquals(Settings.BLUR_RADIUS_RANGE.last, s.shadowBlurRadius)
        assertEquals(Settings.ART_OFFSET_Y_PCT_RANGE.start, s.artOffsetYPct)
    }

    @Test
    fun `sanitized trims a pasted client id`() {
        assertEquals("abc123", Settings(clientId = "  abc123\n").sanitized().clientId)
    }
}
