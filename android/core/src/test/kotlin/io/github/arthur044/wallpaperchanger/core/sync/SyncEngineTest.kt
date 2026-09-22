package io.github.arthur044.wallpaperchanger.core.sync

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.spotify.AuthExpiredException
import io.github.arthur044.wallpaperchanger.core.spotify.RateLimitedException
import io.github.arthur044.wallpaperchanger.core.spotify.TransientNetworkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Poller's Web API path (_run_one_cycle / _handle_now_playing), in virtual time.
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {
    private val source = FakeSource()
    private val sink = FakeSink()
    private val settings = MutableStateFlow(Settings())
    private val memory = FakeMemory()

    private val airbag = NowPlaying(true, "t1", "a1", "https://i.scdn.co/image/a1", "Airbag", "Radiohead")
    private val lucky = airbag.copy(trackId = "t2", trackName = "Lucky")

    private fun TestScope.engine() = SyncEngine(source, sink, settings, memory, timeSource = testScheduler.timeSource)

    // One cycle, then let the wait the engine asked for pass.
    private suspend fun TestScope.cycle(engine: SyncEngine): Duration? =
        engine.runOnce().also { wait -> wait?.let { advanceTimeBy(it) } }

    @Test
    fun `a new track is drawn once, not on every poll`() = runTest {
        val engine = engine()
        source.playing = { airbag }

        repeat(3) { cycle(engine) }

        assertEquals(listOf("t1"), sink.shown)
        assertEquals(SyncStatus.Showing(airbag), engine.status.value)
        assertEquals(3, source.calls)
    }

    @Test
    fun `the next track is drawn again`() = runTest {
        val engine = engine()
        source.playing = { airbag }
        cycle(engine)
        source.playing = { lucky }
        cycle(engine)

        assertEquals(listOf("t1", "t2"), sink.shown)
    }

    @Test
    fun `nothing playing leaves the wallpaper alone`() = runTest {
        val engine = engine()
        source.playing = { null }
        cycle(engine)
        source.playing = { airbag.copy(isPlaying = false) }
        cycle(engine)

        assertTrue(sink.shown.isEmpty())
        assertEquals(SyncStatus.Idle, engine.status.value)
    }

    @Test
    fun `resuming the same track does not redraw it`() = runTest {
        val engine = engine()
        source.playing = { airbag }
        cycle(engine)
        source.playing = { null }
        cycle(engine)
        source.playing = { airbag }
        cycle(engine)

        assertEquals(listOf("t1"), sink.shown)
        assertEquals(SyncStatus.Showing(airbag), engine.status.value)
    }

    @Test
    fun `paused in settings makes no API call`() = runTest {
        val engine = engine()
        settings.value = Settings(paused = true)
        source.playing = { airbag }

        assertEquals(25.seconds, cycle(engine))
        assertEquals(0, source.calls)
        assertEquals(SyncStatus.Paused, engine.status.value)
    }

    @Test
    fun `network errors back off exponentially and reset on success`() = runTest {
        val engine = engine()
        source.playing = { throw TransientNetworkException("offline") }
        val waits = List(4) { cycle(engine) }
        assertEquals(listOf(5.seconds, 10.seconds, 20.seconds, 40.seconds), waits)
        assertTrue(engine.status.value is SyncStatus.Retrying)

        source.playing = { airbag }
        assertEquals(25.seconds, cycle(engine))

        source.playing = { throw TransientNetworkException("offline again") }
        assertEquals(5.seconds, cycle(engine))
    }

    @Test
    fun `a 429 waits for Retry-After and blocks early syncs`() = runTest {
        val engine = engine()
        source.playing = { throw RateLimitedException(90.seconds) }
        assertEquals(90.seconds, engine.runOnce())

        advanceTimeBy(30.seconds)
        source.playing = { airbag }
        engine.runOnce() // e.g. a forced sync: must not hit the API yet
        assertEquals(1, source.calls)

        advanceTimeBy(60.seconds)
        engine.runOnce()
        assertEquals(2, source.calls)
    }

    @Test
    fun `an expired session stops the loop`() = runTest {
        val engine = engine()
        source.playing = { throw AuthExpiredException("invalid_grant") }

        engine.run() // returns instead of looping forever

        assertEquals(1, source.calls)
        assertEquals(SyncStatus.SignedOut, engine.status.value)
    }

    @Test
    fun `a failed render is retried on the next poll`() = runTest {
        val engine = engine()
        source.playing = { airbag }
        sink.failures = 1

        cycle(engine)
        assertTrue(engine.status.value is SyncStatus.RenderFailed)
        assertTrue(sink.shown.isEmpty())

        cycle(engine)
        assertEquals(listOf("t1"), sink.shown)
        assertEquals(SyncStatus.Showing(airbag), engine.status.value)
    }

    @Test
    fun `cancellation inside the render is not swallowed`() = runTest {
        val engine = engine()
        source.playing = { airbag }
        sink.cancel = true

        val thrown = runCatching { engine.runOnce() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `the loop polls once per interval`() = runTest {
        val engine = engine()
        source.playing = { airbag }
        backgroundScope.launch { engine.run() }

        advanceTimeBy(80.seconds) // polls at 0, 25, 50, 75
        runCurrent()
        assertEquals(4, source.calls)
    }

    @Test
    fun `the interval follows the settings`() = runTest {
        val engine = engine()
        settings.value = Settings(webApiPollIntervalSeconds = 60.0)
        source.playing = { airbag }
        backgroundScope.launch { engine.run() }

        advanceTimeBy(59.seconds)
        assertEquals(1, source.calls)
        advanceTimeBy(2.seconds)
        assertEquals(2, source.calls)
    }

    @Test
    fun `sync now polls right away`() = runTest {
        val engine = engine()
        source.playing = { airbag }
        backgroundScope.launch { engine.run() }
        runCurrent()

        advanceTimeBy(10.seconds)
        engine.syncNow()
        runCurrent()
        assertEquals(2, source.calls)
    }

    @Test
    fun `repeated sync now cannot flood the API`() = runTest {
        val engine = engine()
        source.playing = { airbag }
        backgroundScope.launch { engine.run() }
        runCurrent()

        advanceTimeBy(1.seconds)
        engine.syncNow()
        runCurrent()
        assertEquals(1, source.calls)
    }

    @Test
    fun `a track remembered from before a restart is not redrawn`() = runTest {
        memory.saved = "t1"
        val engine = engine()
        source.playing = { airbag }

        cycle(engine)

        assertTrue(sink.shown.isEmpty())
        assertEquals(SyncStatus.Showing(airbag), engine.status.value)
    }

    @Test
    fun `a drawn track is remembered, a failed one is not`() = runTest {
        val engine = engine()
        source.playing = { airbag }
        sink.failures = 1
        cycle(engine)
        assertEquals(null, memory.saved)

        cycle(engine)
        assertEquals("t1", memory.saved)
    }

    @Test
    fun `a blocked wallpaper stops the loop`() = runTest {
        val engine = engine()
        source.playing = { airbag }
        sink.blocked = true

        engine.run() // returns: retrying can't help

        assertEquals(1, source.calls)
        assertTrue(engine.status.value is SyncStatus.Blocked)
    }

    @Test
    fun `the network coming back ends a backoff wait`() = runTest {
        val engine = engine()
        source.playing = { throw TransientNetworkException("offline") }
        backgroundScope.launch { engine.run() }

        advanceTimeBy(45.seconds) // polls at 0, 5, 15, 35; the next would be at 75
        assertEquals(4, source.calls)

        source.playing = { airbag }
        engine.onNetworkAvailable()
        runCurrent()
        assertEquals(5, source.calls)
        assertEquals(SyncStatus.Showing(airbag), engine.status.value)
    }

    @Test
    fun `the network coming back is ignored when nothing failed`() = runTest {
        val engine = engine()
        source.playing = { airbag }
        backgroundScope.launch { engine.run() }
        runCurrent()

        advanceTimeBy(10.seconds)
        engine.onNetworkAvailable()
        runCurrent()
        assertEquals(1, source.calls)
    }

    @Test
    fun `redraw repaints the track on screen without asking Spotify`() = runTest {
        val engine = engine()
        source.playing = { airbag }
        backgroundScope.launch { engine.run() }
        runCurrent()

        advanceTimeBy(2.seconds)
        settings.value = Settings(cornerRadius = 40)
        engine.redraw()
        runCurrent()

        assertEquals(listOf("t1", "t1"), sink.shown)
        assertEquals(1, source.calls)
    }

    @Test
    fun `redraw with nothing on screen draws nothing`() = runTest {
        val engine = engine()
        source.playing = { null }
        backgroundScope.launch { engine.run() }
        runCurrent()

        engine.redraw()
        runCurrent()
        assertTrue(sink.shown.isEmpty())
    }

    @Test
    fun `a redraw requested while stopped still polls first thing on start`() = runTest {
        val engine = engine()
        source.playing = { airbag }
        engine.redraw() // e.g. a setting changed while the screen was off

        engine.runOnce()

        assertEquals(1, source.calls)
        assertEquals(listOf("t1"), sink.shown)
    }

    private class FakeMemory : RenderMemory {
        var saved: String? = null

        override suspend fun lastRenderedTrackId(): String? = saved

        override suspend fun remember(trackId: String?) {
            saved = trackId
        }
    }

    private class FakeSource : NowPlayingSource {
        var playing: () -> NowPlaying? = { null }
        var calls = 0
            private set

        override suspend fun currentlyPlaying(): NowPlaying? {
            calls++
            return playing()
        }
    }

    private class FakeSink : WallpaperSink {
        val shown = mutableListOf<String?>()
        var failures = 0
        var cancel = false
        var blocked = false

        override suspend fun show(nowPlaying: NowPlaying) {
            if (cancel) throw CancellationException("service stopped")
            if (blocked) throw WallpaperBlockedException("device policy")
            if (failures > 0) {
                failures--
                throw IllegalStateException("art download failed")
            }
            shown += nowPlaying.trackId
        }
    }
}
