package io.github.arthur044.wallpaperchanger.share

import android.graphics.Bitmap
import android.util.Log
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.lyrics.Lyrics
import io.github.arthur044.wallpaperchanger.core.lyrics.LyricsState
import io.github.arthur044.wallpaperchanger.core.share.VerseSelection
import io.github.arthur044.wallpaperchanger.core.share.firstSelection
import io.github.arthur044.wallpaperchanger.core.share.tap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Where the share screen stands. */
enum class SharePhase { LOADING, NO_LYRICS, INSTRUMENTAL, NO_NETWORK, CHOOSING, IMAGE_UNAVAILABLE }

data class ShareUiState(
    val open: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val phase: SharePhase = SharePhase.LOADING,
    val lines: List<String> = emptyList(),
    val selection: VerseSelection? = null,
    /** The base is in hand: lines can be chosen. */
    val ready: Boolean = false,
    val preview: Bitmap? = null,
    /** The selection [preview] was drawn for. */
    val previewOf: VerseSelection? = null,
    /** The last tap asked for more than fits on the card. */
    val tooLong: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    /** The system share sheet could not be opened. */
    val shareSheetFailed: Boolean = false,
    /** Saved and waiting for the share sheet; handed over once. */
    val shareFile: File? = null,
)

/** The share screen's phase for [state], or null when [state] is about another track. */
internal fun phaseFor(state: LyricsState, trackId: String?): SharePhase? = when (state) {
    is LyricsState.Ready -> if (state.trackId != trackId) {
        null
    } else {
        when (state.lyrics) {
            is Lyrics.Text -> SharePhase.CHOOSING
            Lyrics.Instrumental -> SharePhase.INSTRUMENTAL
            Lyrics.NotFound -> SharePhase.NO_LYRICS
        }
    }
    is LyricsState.Unavailable -> SharePhase.NO_NETWORK.takeIf { state.trackId == trackId }
    is LyricsState.Loading -> SharePhase.LOADING.takeIf { state.trackId == trackId }
    LyricsState.None -> null
}

/**
 * The share screen's logic, bound to the track it was opened for even if
 * another one starts meanwhile. [lyricsOf] asks for that track's lyrics and
 * follows the answer; [openDrawing] gets the base. Work runs in [scope]; a
 * drawing is released in [releaseScope], which outlives it, after a draw in
 * progress.
 */
class ShareScreenModel(
    private val scope: CoroutineScope,
    private val releaseScope: CoroutineScope,
    private val lyricsOf: (NowPlaying) -> Flow<LyricsState>,
    private val openDrawing: suspend (NowPlaying) -> ShareDrawing,
    private val previewDelayMs: Long = PREVIEW_DEBOUNCE_MS,
) {
    private val mutableState = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = mutableState.asStateFlow()

    private var drawing: ShareDrawing? = null
    private var drawingJob: Job? = null
    private var lyricsJob: Job? = null
    private var previewJob: Job? = null
    private val jobs = mutableListOf<Job>()
    private var openedAt = 0L
    private var firstPreviewLogged = false

    fun open(nowPlaying: NowPlaying) {
        if (mutableState.value.open) return
        openedAt = System.nanoTime()
        firstPreviewLogged = false
        mutableState.value = ShareUiState(open = true, nowPlaying = nowPlaying)
        followLyrics(nowPlaying)
    }

    // Always asks: the early lookup may have been cancelled by a track change
    // (the screen would wait forever), and asking is free when it's kept or out.
    private fun followLyrics(nowPlaying: NowPlaying) {
        lyricsJob?.cancel()
        lyricsJob = scope.launch {
            lyricsOf(nowPlaying).collect { lyrics ->
                val phase = phaseFor(lyrics, nowPlaying.trackId) ?: return@collect
                val lines = ((lyrics as? LyricsState.Ready)?.lyrics as? Lyrics.Text)?.lines.orEmpty()
                mutableState.update { it.copy(phase = phase, lines = lines) }
                if (phase == SharePhase.CHOOSING) prepareDrawing(nowPlaying)
            }
        }
    }

    private fun prepareDrawing(nowPlaying: NowPlaying) {
        if (drawing != null || drawingJob?.isActive == true) return
        drawingJob = scope.launch {
            val opened = try {
                openDrawing(nowPlaying)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No base to share from", e)
                mutableState.update { it.copy(phase = SharePhase.IMAGE_UNAVAILABLE) }
                return@launch
            }
            drawing = opened
            val lines = mutableState.value.lines
            // The first verse that fits alone, so the preview shows without another tap.
            val first = firstSelection(lines) { opened.fits(it.of(lines)) }
            mutableState.update { it.copy(ready = true, selection = first) }
            if (first != null) redrawPreview(opened)
        }
    }

    fun tap(index: Int) {
        val current = drawing ?: return
        val state = mutableState.value
        val result = state.selection.tap(index, state.lines) { current.fits(it.of(state.lines)) }
        mutableState.update {
            it.copy(selection = result.selection, tooLong = result.refused, saveFailed = false, shareSheetFailed = false)
        }
        if (result.selection != state.selection) redrawPreview(current)
    }

    private fun redrawPreview(current: ShareDrawing) {
        previewJob?.cancel()
        val selection = mutableState.value.selection
        if (selection == null) {
            mutableState.update { it.copy(preview = null, previewOf = null) }
            return
        }
        val lines = mutableState.value.lines
        previewJob = scope.launch {
            delay(previewDelayMs) // taps in a row draw once
            val image = try {
                current.draw(selection.of(lines))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                previewFailed(e)
                return@launch
            } catch (e: OutOfMemoryError) {
                previewFailed(e)
                return@launch
            }
            // Once per opening: clearing the selection and choosing again is not a first preview.
            if (!firstPreviewLogged) {
                firstPreviewLogged = true
                Log.d(TAG, "button to first preview: ${(System.nanoTime() - openedAt) / 1_000_000} ms")
            }
            mutableState.update { it.copy(preview = image, previewOf = selection) }
        }
    }

    // A draw that fails (out of memory on the full-size image, say) must not
    // take the app down: the screen says the image can't be made.
    private fun previewFailed(e: Throwable) {
        Log.w(TAG, "Could not draw the share preview", e)
        mutableState.update { it.copy(phase = SharePhase.IMAGE_UNAVAILABLE) }
    }

    fun share() {
        val current = drawing ?: return
        val state = mutableState.value
        val selection = state.selection ?: return
        if (state.saving) return
        mutableState.update { it.copy(saving = true, saveFailed = false, shareSheetFailed = false) }
        jobs += scope.launch {
            try {
                val image = state.preview.takeIf { state.previewOf == selection } ?: current.draw(selection.of(state.lines))
                val file = current.save(image)
                mutableState.update { it.copy(saving = false, shareFile = file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not save the share image", e)
                mutableState.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    /** The share sheet has the file; don't hand it over again (rotation). */
    fun onShareSheetShown() = mutableState.update { it.copy(shareFile = null) }

    /** The share sheet could not be opened for the saved file. */
    fun onShareSheetFailed() = mutableState.update { it.copy(shareFile = null, shareSheetFailed = true) }

    fun retry() {
        val nowPlaying = mutableState.value.nowPlaying ?: return
        mutableState.update { it.copy(phase = SharePhase.LOADING) }
        followLyrics(nowPlaying)
    }

    fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        lyricsJob?.cancel()
        drawingJob?.cancel()
        previewJob?.cancel()
        release()
        mutableState.value = ShareUiState()
    }

    /** Releases the drawing after a draw in progress; [releaseScope] outlives the screen. */
    fun release() {
        val closing = drawing ?: return
        drawing = null
        releaseScope.launch { closing.close() }
    }

    private companion object {
        const val TAG = "LyricsShare"
        const val PREVIEW_DEBOUNCE_MS = 250L
    }
}
