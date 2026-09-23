package io.github.arthur044.wallpaperchanger.core.config

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SettingsSerializerTest {
    private suspend fun decode(json: String): Settings =
        SettingsSerializer.readFrom(ByteArrayInputStream(json.toByteArray()))

    private suspend fun encode(settings: Settings): String =
        ByteArrayOutputStream().also { SettingsSerializer.writeTo(settings, it) }.toString(Charsets.UTF_8)

    @Test
    fun `round-trips every field`() = runTest {
        val s = Settings(
            clientId = "0123456789abcdef0123456789abcdef",
            webApiPollIntervalSeconds = 30.0,
            artSizePct = 0.5,
            cornerRadius = 8,
            shadowBlurRadius = 12,
            showTrackInfo = false,
            syncLockScreen = true,
            artOffsetYPct = 0.1,
            useMediaSession = true,
            paused = true,
            backgroundStyle = BackgroundStyle.MESH,
            artGlow = true,
            textCard = TextCard.GLASS,
            smoothTransition = true,
            artFrame = ArtFrame.DOUBLE,
        )
        assertEquals(s, decode(encode(s)))
    }

    @Test
    fun `writes the desktop config_json key names`() = runTest {
        val json = encode(Settings())
        assertTrue("\"client_id\"" in json, json)
        assertTrue("\"fallback_poll_interval_seconds\"" in json, json)
        assertTrue("\"sync_lock_screen\"" in json, json)
        assertTrue("\"background_style\": \"solid\"" in json, json)
        assertTrue("\"art_glow\"" in json, json)
        assertTrue("\"text_card\": \"none\"" in json, json)
    }

    @Test
    fun `reads the desktop's background style values`() = runTest {
        val s = decode("""{"background_style": "mesh", "art_glow": true, "text_card": "glass"}""")
        assertEquals(BackgroundStyle.MESH, s.backgroundStyle)
        assertTrue(s.artGlow)
        assertEquals(TextCard.GLASS, s.textCard)
    }

    @Test
    fun `reads the desktop's blurred background and frame values`() = runTest {
        val s = decode("""{"background_style": "blur", "art_frame": "single"}""")
        assertEquals(BackgroundStyle.BLUR, s.backgroundStyle)
        assertEquals(ArtFrame.SINGLE, s.artFrame)
    }

    @Test
    fun `an unknown style value falls back to its default, not to corruption`() = runTest {
        val s = decode("""{"background_style": "plasma", "text_card": "neon", "corner_radius": 40}""")
        assertEquals(BackgroundStyle.SOLID, s.backgroundStyle)
        assertEquals(TextCard.NONE, s.textCard)
        assertEquals(40, s.cornerRadius)
    }

    @Test
    fun `missing keys fall back to defaults`() = runTest {
        assertEquals(Settings(clientId = "abc"), decode("""{"client_id": "abc"}"""))
    }

    @Test
    fun `null values fall back to defaults`() = runTest {
        assertEquals(Settings(), decode("""{"art_size_pct": null}"""))
    }

    @Test
    fun `accepts a desktop config_json, ignoring keys android has no use for`() = runTest {
        val desktop = """
            {
              "client_id": "0123456789abcdef0123456789abcdef",
              "redirect_uri": "http://127.0.0.1:8888/callback",
              "scope": "user-read-currently-playing user-read-playback-state",
              "poll_interval_seconds": 4.0,
              "use_smtc": true,
              "fallback_poll_interval_seconds": 25.0,
              "art_size_pct": 0.68,
              "corner_radius": 16,
              "shadow_blur_radius": 24,
              "show_track_info": true,
              "fallback_resolution": [1920, 1080],
              "log_level": "INFO",
              "sync_lock_screen": true
            }
        """.trimIndent()

        assertEquals(
            Settings(clientId = "0123456789abcdef0123456789abcdef", syncLockScreen = true),
            decode(desktop),
        )
    }

    @Test
    fun `out-of-range values are clamped on read`() = runTest {
        assertEquals(Settings.ART_SIZE_PCT_RANGE.endInclusive, decode("""{"art_size_pct": 5.0}""").artSizePct)
    }

    @Test
    fun `a wrongly typed value is reported as corruption`() = runTest {
        assertThrows<CorruptionException> { decode("""{"art_size_pct": "big"}""") }
    }

    @Test
    fun `malformed or empty content is reported as corruption`() = runTest {
        assertThrows<CorruptionException> { decode("""{"client_id": """) }
        assertThrows<CorruptionException> { decode("") }
    }
}
