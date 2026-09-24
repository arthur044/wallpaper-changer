package io.github.arthur044.wallpaperchanger.core.render

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.config.ArtFrame
import io.github.arthur044.wallpaperchanger.core.config.BackgroundStyle
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.config.TextCard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class FrameContentTest {
    private val airbag = NowPlaying(true, "t1", "a1", "https://i.scdn.co/image/a1", "Airbag", "Radiohead")
    private val base = frameContent(airbag, Settings())

    @Test
    fun `settings that never touch pixels keep the content`() {
        val other = Settings(
            clientId = "x",
            webApiPollIntervalSeconds = 60.0,
            syncLockScreen = true,
            useMediaSession = true,
            paused = true,
            syncEnabled = true,
            onboardingDone = true,
            localOnly = true,
            smoothTransition = true,
        )

        assertEquals(base, frameContent(airbag, other))
    }

    @Test
    fun `pausing the music keeps the content`() {
        assertEquals(base, frameContent(airbag.copy(isPlaying = false), Settings()))
    }

    @Test
    fun `another track is other content`() {
        assertNotEquals(base, frameContent(airbag.copy(trackId = "t2", trackName = "Lucky"), Settings()))
    }

    @Test
    fun `every look setting is other content`() {
        val looks = listOf(
            Settings(artSizePct = 0.5),
            Settings(cornerRadius = 40),
            Settings(shadowBlurRadius = 0),
            Settings(artOffsetYPct = 0.1),
            Settings(showTrackInfo = false),
            Settings(backgroundStyle = BackgroundStyle.BLUR),
            Settings(artGlow = true),
            Settings(textCard = TextCard.entries.last()),
            Settings(artFrame = ArtFrame.entries.last()),
        )
        looks.forEach { assertNotEquals(base, frameContent(airbag, it), "$it") }
        assertNotEquals(
            frameContent(airbag, Settings(backgroundStyle = BackgroundStyle.BLUR)),
            frameContent(airbag, Settings(backgroundStyle = BackgroundStyle.BLUR, blurStrength = 90)),
        )
    }
}
