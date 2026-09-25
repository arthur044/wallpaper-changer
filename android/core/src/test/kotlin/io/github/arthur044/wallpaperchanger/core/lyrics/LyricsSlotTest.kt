package io.github.arthur044.wallpaperchanger.core.lyrics

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsSlotTest {
    private val source = FakeSource()
    private val slot = LyricsSlot(source)

    private val airbag = NowPlaying(true, "t1", "a1", null, "Airbag", "Radiohead", "OK Computer", 287_000)
    private val lucky = airbag.copy(trackId = "t2", trackName = "Lucky")
    private val words = Lyrics.Text(listOf("Placeholder line 1"))

    @Test
    fun `an answer is kept for its track, no lyrics included`() = runTest {
        source.answer = { Lyrics.NotFound }

        assertEquals(Lyrics.NotFound, slot.lyricsFor(airbag))
        assertEquals(Lyrics.NotFound, slot.lyricsFor(airbag))

        assertEquals(1, source.calls)
        assertEquals(Lyrics.NotFound, slot.peek("t1"))
    }

    @Test
    fun `another track drops the kept answer`() = runTest {
        source.answer = { words }
        slot.lyricsFor(airbag)

        slot.onTrack("t2")
        assertNull(slot.peek("t1"))

        // Back to the first track: only one position, so it is asked again.
        slot.lyricsFor(lucky)
        slot.lyricsFor(airbag)
        assertEquals(3, source.calls)
    }

    @Test
    fun `the same track showing again keeps the answer`() = runTest {
        source.answer = { words }
        slot.lyricsFor(airbag)

        slot.onTrack("t1")

        assertEquals(words, slot.peek("t1"))
    }

    @Test
    fun `a failed lookup is not kept, so the next ask tries again`() = runTest {
        source.answer = { throw LyricsUnavailableException("offline") }
        assertThrows<LyricsUnavailableException> { slot.lyricsFor(airbag) }
        assertNull(slot.peek("t1"))

        source.answer = { words }
        assertEquals(words, slot.lyricsFor(airbag))
        assertEquals(2, source.calls)
    }

    @Test
    fun `an answer arriving after the track changed is not kept`() = runTest {
        val gate = CompletableDeferred<Lyrics>()
        source.answer = { gate.await() }
        val late = async { slot.lyricsFor(airbag) }
        runCurrent()

        slot.onTrack("t2")
        gate.complete(words)

        assertEquals(words, late.await(), "its caller still gets it")
        assertNull(slot.peek("t1"))
        assertNull(slot.peek("t2"))
    }

    @Test
    fun `asking twice while the first lookup is out makes one call`() = runTest {
        val gate = CompletableDeferred<Lyrics>()
        source.answer = { gate.await() }
        val early = async { slot.lyricsFor(airbag) }
        val button = async { slot.lyricsFor(airbag) }
        runCurrent()

        gate.complete(words)

        assertEquals(words, early.await())
        assertEquals(words, button.await())
        assertEquals(1, source.calls)
    }

    @Test
    fun `a track with nothing to look up by asks nobody`() = runTest {
        assertEquals(Lyrics.NotFound, slot.lyricsFor(airbag.copy(trackId = null)))
        assertEquals(Lyrics.NotFound, slot.lyricsFor(airbag.copy(trackName = null)))
        assertEquals(0, source.calls)
    }

    private class FakeSource : LyricsSource {
        var calls = 0
        var answer: suspend () -> Lyrics = { Lyrics.NotFound }

        override suspend fun lyrics(query: LyricsQuery): Lyrics {
            calls++
            return answer()
        }
    }
}
