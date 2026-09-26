package io.github.arthur044.wallpaperchanger.share

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.lyrics.Lyrics
import io.github.arthur044.wallpaperchanger.core.lyrics.LyricsState
import io.github.arthur044.wallpaperchanger.core.share.VerseSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// The share screen's logic with a fake lyrics lookup and a fake drawing. The
// model works in backgroundScope, which advanceUntilIdle() does not wait for:
// runCurrent() runs it (every delay here is 0).
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShareScreenModelTest {
    private val airbag = NowPlaying(true, "t1", "a1", null, "Airbag", "Radiohead")
    private val lines = listOf("Placeholder line one", "Placeholder line two", "", "Placeholder line three")
    private val words = Lyrics.Text(lines)
    private val drawing = FakeDrawing()

    private fun TestScope.model(
        lyrics: (NowPlaying) -> Flow<LyricsState> = { flowOf(LyricsState.Loading("t1"), LyricsState.Ready("t1", words)) },
        open: suspend (NowPlaying) -> ShareDrawing = { drawing },
    ) = ShareScreenModel(backgroundScope, backgroundScope, lyrics, open, previewDelayMs = 0)

    @Test
    fun openingGetsToChoosingWithTheFirstVerseAndItsPreview() = runTest {
        val model = model()
        model.open(airbag)
        runCurrent()

        val state = model.state.value
        assertEquals(SharePhase.CHOOSING, state.phase)
        assertTrue(state.ready)
        assertEquals(VerseSelection(0, 0), state.selection)
        assertNotNull(state.preview)
        assertEquals(1, drawing.draws)
    }

    @Test
    fun aScreenOpenedBeforeTheLyricsArriveWaitsForItsOwnTrack() = runTest {
        // The screen asks for its own track (review #1): it shows loading
        // until that answer comes, whatever the early lookup does meanwhile.
        val answer = CompletableDeferred<LyricsState>()
        val model = model(lyrics = { flow { emit(LyricsState.Loading("t1")); emit(answer.await()) } })
        model.open(airbag)
        runCurrent()
        assertEquals(SharePhase.LOADING, model.state.value.phase)

        answer.complete(LyricsState.Ready("t1", words))
        runCurrent()

        assertEquals(SharePhase.CHOOSING, model.state.value.phase)
    }

    @Test
    fun noBaseMeansTheImageCantBeMade() = runTest {
        val model = model(open = { throw IllegalStateException("no art") })
        model.open(airbag)
        runCurrent()

        assertEquals(SharePhase.IMAGE_UNAVAILABLE, model.state.value.phase)
        assertFalse(model.state.value.ready)
    }

    @Test
    fun aFirstVerseTooLongForTheCardIsSkippedAtTheStart() = runTest {
        drawing.fitsWhen = { it != listOf(lines[0]) }
        val model = model()
        model.open(airbag)
        runCurrent()

        assertEquals(VerseSelection(1, 1), model.state.value.selection)
        assertFalse(model.state.value.tooLong)
    }

    @Test
    fun aTapAskingForMoreThanFitsIsRefused() = runTest {
        drawing.fitsWhen = { it.size <= 1 }
        val model = model()
        model.open(airbag)
        runCurrent()

        model.tap(1)

        assertTrue(model.state.value.tooLong)
        assertEquals(VerseSelection(0, 0), model.state.value.selection)
    }

    @Test
    fun aPreviewThatFailsToDrawDoesNotCrash() = runTest {
        drawing.failDraw = true
        val model = model()
        model.open(airbag)
        runCurrent()

        assertEquals(SharePhase.IMAGE_UNAVAILABLE, model.state.value.phase)
    }

    @Test
    fun sharingSavesThePreviewAndHandsTheFileOverOnce() = runTest {
        val model = model()
        model.open(airbag)
        runCurrent()

        model.share()
        runCurrent()

        assertNotNull(model.state.value.shareFile)
        assertEquals("the preview is saved, not drawn again", 1, drawing.draws)
        model.onShareSheetShown()
        assertNull(model.state.value.shareFile)
    }

    @Test
    fun aShareSheetThatCantOpenIsReported() = runTest {
        val model = model()
        model.open(airbag)
        runCurrent()
        model.share()
        runCurrent()

        model.onShareSheetFailed()

        assertTrue(model.state.value.shareSheetFailed)
        assertNull(model.state.value.shareFile)
    }

    @Test
    fun tryingAgainAfterNoNetworkAsksAgain() = runTest {
        var asked = 0
        val model = model(lyrics = {
            asked++
            flowOf(if (asked == 1) LyricsState.Unavailable("t1") else LyricsState.Ready("t1", words))
        })
        model.open(airbag)
        runCurrent()
        assertEquals(SharePhase.NO_NETWORK, model.state.value.phase)

        model.retry()
        runCurrent()

        assertEquals(SharePhase.CHOOSING, model.state.value.phase)
        assertEquals(2, asked)
    }

    @Test
    fun closingReleasesTheDrawingAndResets() = runTest {
        val model = model()
        model.open(airbag)
        runCurrent()

        model.close()
        runCurrent()

        assertTrue(drawing.closed)
        assertEquals(ShareUiState(), model.state.value)
    }

    private class FakeDrawing : ShareDrawing {
        var fitsWhen: (List<String>) -> Boolean = { true }
        var failDraw = false
        var draws = 0
        var closed = false

        override fun fits(verses: List<String>) = fitsWhen(verses)

        override suspend fun draw(verses: List<String>): Bitmap {
            draws++
            if (failDraw) throw OutOfMemoryError("test")
            return Bitmap.createBitmap(9, 16, Bitmap.Config.ARGB_8888)
        }

        override suspend fun save(image: Bitmap): File = File("share.jpg")

        override suspend fun close() {
            closed = true
        }
    }
}
