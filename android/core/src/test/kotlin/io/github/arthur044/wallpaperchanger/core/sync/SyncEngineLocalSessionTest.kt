package io.github.arthur044.wallpaperchanger.core.sync

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.ResolvedAlbum
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.spotify.AlbumTrack
import io.github.arthur044.wallpaperchanger.core.spotify.AuthExpiredException
import io.github.arthur044.wallpaperchanger.core.spotify.RateLimitedException
import io.github.arthur044.wallpaperchanger.core.spotify.TransientNetworkException
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
import kotlin.time.Duration.Companion.seconds

// The hybrid path (Poller._handle_smtc_snapshot): the phone's own Spotify
// session drives change detection, the Web API only resolves unknown albums.
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineLocalSessionTest {
    private val source = FakeSource()
    private val tracks = FakeAlbumTracks()
    private val sink = FakeSink()
    private val settings = MutableStateFlow(Settings(useMediaSession = true))

    private val apiSaysAirbag = NowPlaying(true, "t1", "a1", "https://i.scdn.co/image/a1", "Airbag", "Radiohead")
    private val airbagLocally = LocalTrack("Airbag", "Radiohead", isPlaying = true)
    private val luckyLocally = LocalTrack("Lucky", "Radiohead", isPlaying = true)

    private val store = FakeStore()

    private fun TestScope.engine() =
        SyncEngine(source, sink, settings, albumTracks = tracks, trackIndexStore = store, timeSource = testScheduler.timeSource)

    @Test
    fun `the first track of an album is resolved once, then the album is free`() = runTest {
        val engine = engine()
        source.playing = { apiSaysAirbag }
        tracks.byAlbum = mapOf("a1" to listOf(AlbumTrack("Airbag", "Radiohead"), AlbumTrack("Lucky", "Radiohead")))

        engine.onLocalTrack(airbagLocally)
        engine.runOnce()
        assertEquals(listOf("radiohead::airbag"), sink.shown)
        assertEquals(1, source.calls)

        // Another track of the same album: known from the prefetch, no call at all.
        advanceTimeBy(1.seconds)
        engine.onLocalTrack(luckyLocally)
        engine.runOnce()
        assertEquals(listOf("radiohead::airbag", "radiohead::lucky"), sink.shown)
        assertEquals(1, source.calls)
        assertEquals(1, tracks.calls)
    }

    @Test
    fun `a local track change is drawn at once, without waiting for a poll`() = runTest {
        val engine = engine()
        source.playing = { apiSaysAirbag }
        tracks.byAlbum = mapOf("a1" to listOf(AlbumTrack("Airbag", "Radiohead"), AlbumTrack("Lucky", "Radiohead")))
        backgroundScope.launch { engine.run() }
        engine.onLocalTrack(airbagLocally)
        runCurrent()

        advanceTimeBy(2.seconds) // far from the 25s poll
        engine.onLocalTrack(luckyLocally)
        runCurrent()

        assertEquals(listOf("radiohead::airbag", "radiohead::lucky"), sink.shown)
        assertEquals(2_000, testScheduler.currentTime)
    }

    @Test
    fun `a track the API can't place yet is retried soon, not a poll later`() = runTest {
        val engine = engine()
        source.playing = { null } // the API hasn't caught up with the new album

        engine.onLocalTrack(airbagLocally)
        // 5s, then 10s, 20s, capped at the poll interval: the API usually
        // catches up within seconds, and waiting 25s would throw the gain away.
        assertEquals(5.seconds, engine.runOnce())
        assertTrue(sink.shown.isEmpty())
        advanceTimeBy(5.seconds)
        assertEquals(10.seconds, engine.runOnce())
        advanceTimeBy(10.seconds)
        assertEquals(20.seconds, engine.runOnce())
        advanceTimeBy(20.seconds)
        assertEquals(25.seconds, engine.runOnce()) // never slower than a normal poll

        advanceTimeBy(25.seconds)
        source.playing = { apiSaysAirbag }
        assertEquals(25.seconds, engine.runOnce())
        assertEquals(listOf("radiohead::airbag"), sink.shown)
    }

    @Test
    fun `resolution failures don't draw and don't mark the track`() = runTest {
        val engine = engine()
        source.playing = { throw TransientNetworkException("offline") }

        engine.onLocalTrack(airbagLocally)
        assertEquals(5.seconds, engine.runOnce()) // backoff, as in the API path
        assertTrue(sink.shown.isEmpty())
        assertTrue(engine.status.value is SyncStatus.Retrying)

        advanceTimeBy(10.seconds)
        source.playing = { apiSaysAirbag }
        engine.runOnce()
        assertEquals(listOf("radiohead::airbag"), sink.shown)
    }

    @Test
    fun `a rate limit while resolving waits out Retry-After`() = runTest {
        val engine = engine()
        source.playing = { throw RateLimitedException(90.seconds) }

        engine.onLocalTrack(airbagLocally)
        assertEquals(90.seconds, engine.runOnce())
        assertTrue(sink.shown.isEmpty())

        advanceTimeBy(30.seconds)
        source.playing = { apiSaysAirbag }
        engine.runOnce() // still inside the window: no call, no draw
        assertEquals(1, source.calls)

        advanceTimeBy(60.seconds)
        engine.runOnce()
        assertEquals(listOf("radiohead::airbag"), sink.shown)
    }

    @Test
    fun `a revoked session while resolving stops the loop`() = runTest {
        val engine = engine()
        source.playing = { throw AuthExpiredException("invalid_grant") }

        engine.onLocalTrack(airbagLocally)
        engine.run() // returns instead of looping

        assertEquals(SyncStatus.SignedOut, engine.status.value)
        assertTrue(sink.shown.isEmpty())
    }

    @Test
    fun `playback on another device falls back to the API`() = runTest {
        val engine = engine()
        source.playing = { apiSaysAirbag }

        // The phone's session is paused: what it reports is stale.
        engine.onLocalTrack(LocalTrack("In Excelsis", "ANGRA", isPlaying = false))
        engine.runOnce()

        assertEquals(listOf("t1"), sink.shown) // the API's track, not the stale one
        assertEquals(1, source.calls)
    }

    @Test
    fun `local-only never asks Spotify about other devices`() = runTest {
        settings.value = Settings(useMediaSession = true, localOnly = true)
        val engine = engine()
        source.playing = { apiSaysAirbag }

        engine.onLocalTrack(LocalTrack("In Excelsis", "ANGRA", isPlaying = false))
        engine.runOnce()

        assertEquals(0, source.calls)
        assertTrue(sink.shown.isEmpty())
        assertEquals(SyncStatus.Idle, engine.status.value)
    }

    @Test
    fun `local-only still resolves albums for what plays here`() = runTest {
        settings.value = Settings(useMediaSession = true, localOnly = true)
        val engine = engine()
        source.playing = { apiSaysAirbag }

        engine.onLocalTrack(airbagLocally)
        engine.runOnce()

        assertEquals(listOf("radiohead::airbag"), sink.shown)
        assertEquals(1, source.calls)
    }

    @Test
    fun `with the option off the local session is ignored`() = runTest {
        settings.value = Settings(useMediaSession = false)
        val engine = engine()
        source.playing = { apiSaysAirbag }

        engine.onLocalTrack(luckyLocally)
        engine.runOnce()

        assertEquals(listOf("t1"), sink.shown) // by trackId from the API, not the local key
        assertEquals(1, source.calls)
    }

    @Test
    fun `the wallpaper is drawn before the album's tracklist is fetched`() = runTest {
        // The tracklist only saves calls for the album's other songs; the
        // wallpaper shouldn't wait on it.
        val engine = engine()
        val order = mutableListOf<String>()
        source.playing = { apiSaysAirbag }
        tracks.byAlbum = mapOf("a1" to listOf(AlbumTrack("Airbag", "Radiohead"), AlbumTrack("Lucky", "Radiohead")))
        tracks.onCall = { order += "tracklist" }
        sink.onShow = { order += "drawn" }

        engine.onLocalTrack(airbagLocally)
        engine.runOnce()

        assertEquals(listOf("drawn", "tracklist"), order)
    }

    @Test
    fun `after a restart a song of a known album needs no lookup`() = runTest {
        store.saved = listOf("radiohead::airbag" to ResolvedAlbum("a1", "https://i.scdn.co/image/a1"))
        val engine = engine()

        engine.onLocalTrack(airbagLocally)
        engine.runOnce()

        assertEquals(listOf("radiohead::airbag"), sink.shown)
        assertEquals(0, source.calls)
    }

    @Test
    fun `a newly resolved album is saved`() = runTest {
        val engine = engine()
        source.playing = { apiSaysAirbag }
        tracks.byAlbum = mapOf("a1" to listOf(AlbumTrack("Airbag", "Radiohead"), AlbumTrack("Lucky", "Radiohead")))

        engine.onLocalTrack(airbagLocally)
        engine.runOnce()

        assertEquals(setOf("radiohead::airbag", "radiohead::lucky"), store.saved.map { it.first }.toSet())
    }

    @Test
    fun `a saved song that fails to draw is forgotten, once`() = runTest {
        store.saved = listOf("radiohead::airbag" to ResolvedAlbum("a1", "https://gone"))
        sink.fail = true
        val engine = engine()

        engine.onLocalTrack(airbagLocally)
        engine.runOnce()

        assertTrue(store.saved.isEmpty(), "forgotten, so the next attempt asks Spotify")
    }

    @Test
    fun `the wallpaper is drawn before the index is written`() = runTest {
        val events = mutableListOf<String>()
        sink.onShow = { events += "show" }
        store.onSave = { events += "save" }
        source.playing = { apiSaysAirbag }
        tracks.byAlbum = mapOf("a1" to listOf(AlbumTrack("Airbag", "Radiohead"), AlbumTrack("Lucky", "Radiohead")))
        val engine = engine()

        engine.onLocalTrack(airbagLocally)
        engine.runOnce()

        assertEquals(listOf("show", "save"), events, "one write, after the draw")
    }

    @Test
    fun `a song is forgotten only once while it keeps failing`() = runTest {
        store.saved = listOf("radiohead::airbag" to ResolvedAlbum("a1", "https://gone"))
        sink.fail = true
        source.playing = { apiSaysAirbag }
        val engine = engine()

        engine.onLocalTrack(airbagLocally)
        engine.runOnce() // forgets the saved link
        advanceTimeBy(6.seconds)
        engine.runOnce() // looks it up again; the draw still fails

        assertEquals(1, source.calls)
        assertEquals(listOf("radiohead::airbag" to ResolvedAlbum("a1", "https://i.scdn.co/image/a1")), store.saved)
    }

    @Test
    fun `a song that draws again can be forgotten again later`() = runTest {
        // A link that works today can expire months from now.
        store.saved = listOf("radiohead::airbag" to ResolvedAlbum("a1", "https://gone"))
        sink.fail = true
        source.playing = { apiSaysAirbag }
        val engine = engine()
        engine.onLocalTrack(airbagLocally)
        engine.runOnce() // fails: forgotten
        sink.fail = false
        advanceTimeBy(6.seconds)
        engine.runOnce() // looked up again, drawn
        assertEquals(listOf("radiohead::airbag"), sink.shown)

        sink.fail = true
        engine.redraw() // e.g. a look change
        engine.runOnce()

        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `a failed tracklist prefetch still draws the current track`() = runTest {
        val engine = engine()
        source.playing = { apiSaysAirbag }
        tracks.fail = true

        engine.onLocalTrack(airbagLocally)
        engine.runOnce()

        assertEquals(listOf("radiohead::airbag"), sink.shown)
        advanceTimeBy(6.seconds)
        engine.onLocalTrack(luckyLocally)
        engine.runOnce()
        assertEquals(2, source.calls) // the next track needed its own resolution
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

    private class FakeAlbumTracks : AlbumTracksSource {
        var byAlbum: Map<String, List<AlbumTrack>> = emptyMap()
        var fail = false
        var onCall: () -> Unit = {}
        var calls = 0
            private set

        override suspend fun albumTracks(albumId: String): List<AlbumTrack> {
            calls++
            onCall()
            if (fail) throw TransientNetworkException("tracklist failed")
            return byAlbum[albumId].orEmpty()
        }
    }

    private class FakeSink : WallpaperSink {
        val shown = mutableListOf<String?>()
        var onShow: () -> Unit = {}
        var fail = false

        override suspend fun show(nowPlaying: NowPlaying) {
            if (fail) throw IllegalStateException("could not draw")
            onShow()
            shown += nowPlaying.trackId
        }
    }

    private class FakeStore : TrackIndexStore {
        var saved: List<Pair<String, ResolvedAlbum>> = emptyList()
        var onSave: () -> Unit = {}

        override suspend fun load(): List<Pair<String, ResolvedAlbum>> = saved

        override suspend fun save(entries: List<Pair<String, ResolvedAlbum>>) {
            onSave()
            saved = entries
        }
    }
}
