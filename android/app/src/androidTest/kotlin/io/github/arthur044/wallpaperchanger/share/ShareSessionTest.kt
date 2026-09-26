package io.github.arthur044.wallpaperchanger.share

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// The base bitmap is recycled on close, never under a draw that is still using it.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShareSessionTest {
    private fun bitmap() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    private fun session(base: Bitmap, render: suspend (List<String>) -> Bitmap = { bitmap() }) =
        ShareSession(base, fitsCheck = { true }, render = render, store = { File("unused") })

    @Test
    fun closingWaitsForADrawInProgress() = runTest {
        val base = bitmap()
        val drawing = CompletableDeferred<Unit>()
        val session = session(base) { drawing.await(); bitmap() }

        launch { session.draw(listOf("a")) }
        runCurrent()
        val closing = launch { session.close() }
        runCurrent()

        assertFalse("recycled under a draw", base.isRecycled)
        assertFalse(closing.isCompleted)
        drawing.complete(Unit)
        advanceUntilIdle()
        assertTrue(base.isRecycled)
    }

    @Test
    fun drawingAfterCloseFails() = runTest {
        val session = session(bitmap())
        session.close()

        val thrown = runCatching { session.draw(listOf("a")) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is IllegalStateException)
    }

    @Test
    fun closingTwiceIsFine() = runTest {
        val base = bitmap()
        val session = session(base)
        session.close()
        session.close()
        assertTrue(base.isRecycled)
    }
}
