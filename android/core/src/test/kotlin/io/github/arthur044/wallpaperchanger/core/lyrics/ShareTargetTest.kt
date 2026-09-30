package io.github.arthur044.wallpaperchanger.core.lyrics

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.sync.SyncStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ShareTargetTest {
    private val airbag = NowPlaying(true, "t1", "a1", "https://i.scdn.co/image/a1", "Airbag", "Radiohead")
    private val lucky = airbag.copy(trackId = "t2", trackName = "Lucky")

    @Test
    fun `the track playing is the one to share`() {
        assertEquals(airbag, shareTarget(SyncStatus.Showing(airbag), onScreen = lucky))
    }

    @Test
    fun `with the music paused the track still on the wallpaper is the one to share`() {
        assertEquals(airbag, shareTarget(SyncStatus.Idle, onScreen = airbag))
    }

    @Test
    fun `paused with nothing on the wallpaper yet there is nothing to share`() {
        assertEquals(null, shareTarget(SyncStatus.Idle, onScreen = null))
    }

    @Test
    fun `when the wallpaper is not being kept up there is no button`() {
        val failing = listOf(
            SyncStatus.Starting,
            SyncStatus.Paused,
            SyncStatus.SignedOut,
            SyncStatus.Blocked("no permission"),
            SyncStatus.RenderFailed(lucky, RuntimeException("x")),
            SyncStatus.Retrying(5.seconds, RuntimeException("x")),
            SyncStatus.Failing(5.seconds, RuntimeException("x")),
        )
        failing.forEach { assertEquals(null, shareTarget(it, onScreen = airbag), it.toString()) }
    }
}
