package io.github.arthur044.wallpaperchanger.core.lyrics

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The early lookup follows what the main screen shows, and only while it follows.
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsPrefetchTest {
    private val source = FakeSource()
    private val prefetch = LyricsPrefetch(LyricsSlot(source))

    private val airbag = NowPlaying(true, "t1", "a1", null, "Airbag", "Radiohead", "OK Computer", 287_000)
    private val lucky = airbag.copy(trackId = "t2", trackName = "Lucky")
    private val words = Lyrics.Text(listOf("Placeholder line 1"))
    private val status = MutableStateFlow<SyncStatus>(SyncStatus.Starting)

    private fun TestScope.followWhileOpen() = backgroundScope.launch { prefetch.follow(status) }

    @Test
    fun `a track on screen is looked up before anyone asks`() = runTest {
        source.answer = { words }
        followWhileOpen()

        status.value = SyncStatus.Showing(airbag)
        runCurrent()

        assertEquals(LyricsState.Ready("t1", words), prefetch.state.value)
        assertEquals(listOf("Airbag"), source.asked)
    }

    @Test
    fun `nothing is looked up while nothing is shown`() = runTest {
        followWhileOpen()

        status.value = SyncStatus.Idle
        runCurrent()
        status.value = SyncStatus.Paused
        runCurrent()

        assertEquals(LyricsState.None, prefetch.state.value)
        assertTrue(source.asked.isEmpty())
    }

    @Test
    fun `pausing the music keeps the lyrics of the track still on the wallpaper`() = runTest {
        source.answer = { words }
        followWhileOpen()
        status.value = SyncStatus.Showing(airbag)
        runCurrent()

        status.value = SyncStatus.Idle
        runCurrent()
        status.value = SyncStatus.Showing(airbag)
        runCurrent()

        assertEquals(LyricsState.Ready("t1", words), prefetch.state.value)
        assertEquals(1, source.asked.size)
    }

    @Test
    fun `the same track reported again with more details does not restart its lookup`() = runTest {
        // The phone's session often sends the duration a moment after the
        // title, while the first lookup is still out.
        val answer = CompletableDeferred<Lyrics>()
        source.answer = { answer.await() }
        followWhileOpen()
        status.value = SyncStatus.Showing(airbag.copy(durationMs = null))
        runCurrent()

        status.value = SyncStatus.Showing(airbag)
        runCurrent()
        answer.complete(words)
        runCurrent()

        assertEquals(1, source.asked.size)
        assertEquals(0, source.cancelled)
        assertEquals(LyricsState.Ready("t1", words), prefetch.state.value)
    }

    @Test
    fun `a new track cancels the lookup still out for the old one`() = runTest {
        val never = CompletableDeferred<Lyrics>()
        source.answer = { never.await() }
        followWhileOpen()
        status.value = SyncStatus.Showing(airbag)
        runCurrent()
        assertEquals(LyricsState.Loading("t1"), prefetch.state.value)

        source.answer = { words }
        status.value = SyncStatus.Showing(lucky)
        runCurrent()

        assertEquals(LyricsState.Ready("t2", words), prefetch.state.value)
        assertEquals(1, source.cancelled)
    }

    @Test
    fun `a failed lookup says so`() = runTest {
        source.answer = { throw LyricsUnavailableException("offline") }
        followWhileOpen()

        status.value = SyncStatus.Showing(airbag)
        runCurrent()

        assertEquals(LyricsState.Unavailable("t1"), prefetch.state.value)
    }

    @Test
    fun `nothing is asked while the screen is closed, and reopening reuses the answer`() = runTest {
        source.answer = { words }
        val open = followWhileOpen()
        status.value = SyncStatus.Showing(airbag)
        runCurrent()
        open.cancel() // the screen stopped (closed, locked, or another screen)

        status.value = SyncStatus.Showing(lucky)
        runCurrent()
        assertEquals(1, source.asked.size, "a track change with the screen closed asks nobody")

        status.value = SyncStatus.Showing(airbag)
        followWhileOpen()
        runCurrent()
        assertEquals(1, source.asked.size, "the same track on reopening is already known")
        assertEquals(LyricsState.Ready("t1", words), prefetch.state.value)

        status.value = SyncStatus.Showing(lucky)
        runCurrent()
        assertEquals(listOf("Airbag", "Lucky"), source.asked)
    }

    @Test
    fun `a lookup cut short by closing the screen is asked again on reopening`() = runTest {
        val never = CompletableDeferred<Lyrics>()
        source.answer = { never.await() }
        val open = followWhileOpen()
        status.value = SyncStatus.Showing(airbag)
        runCurrent()
        open.cancel()
        runCurrent()

        source.answer = { words }
        followWhileOpen()
        runCurrent()

        assertEquals(LyricsState.Ready("t1", words), prefetch.state.value)
        assertEquals(2, source.asked.size)
    }

    @Test
    fun `trying again after a failure asks again`() = runTest {
        source.answer = { throw LyricsUnavailableException("offline") }
        followWhileOpen()
        status.value = SyncStatus.Showing(airbag)
        runCurrent()

        source.answer = { words }
        prefetch.request(airbag)

        assertEquals(LyricsState.Ready("t1", words), prefetch.state.value)
        assertEquals(2, source.asked.size)
    }

    @Test
    fun `asking for lyrics already kept asks nobody`() = runTest {
        source.answer = { words }
        followWhileOpen()
        status.value = SyncStatus.Showing(airbag)
        runCurrent()

        prefetch.request(airbag)

        assertEquals(1, source.asked.size)
    }

    @Test
    fun `asking while the early lookup is still out waits for it`() = runTest {
        val answer = CompletableDeferred<Lyrics>()
        source.answer = { answer.await() }
        followWhileOpen()
        status.value = SyncStatus.Showing(airbag)
        runCurrent()

        val button = backgroundScope.launch { prefetch.request(airbag) }
        runCurrent()
        answer.complete(words)
        runCurrent()

        assertTrue(button.isCompleted)
        assertEquals(1, source.asked.size)
        assertEquals(LyricsState.Ready("t1", words), prefetch.state.value)
    }

    private class FakeSource : LyricsSource {
        val asked = mutableListOf<String>()
        var cancelled = 0
        var answer: suspend () -> Lyrics = { Lyrics.NotFound }

        override suspend fun lyrics(query: LyricsQuery): Lyrics {
            asked += query.title
            try {
                return answer()
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
        }
    }
}
