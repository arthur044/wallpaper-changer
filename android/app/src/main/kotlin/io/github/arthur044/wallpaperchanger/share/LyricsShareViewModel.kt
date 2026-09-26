package io.github.arthur044.wallpaperchanger.share

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.arthur044.wallpaperchanger.WallpaperApp
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.lyrics.Lyrics
import io.github.arthur044.wallpaperchanger.core.lyrics.LyricsState
import io.github.arthur044.wallpaperchanger.core.share.VerseSelection
import io.github.arthur044.wallpaperchanger.core.share.tap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * The share screen's state, per activity: rotation, folding and a zoom
 * (density) change keep the lyrics, the selection and the image drawn; none
 * is looked up or drawn again. The screen is bound to the track it was
 * opened for, even if another one starts meanwhile.
 */
class LyricsShareViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as WallpaperApp).container
    private val mutableState = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = mutableState.asStateFlow()

    private var session: ShareSession? = null
    private var sessionJob: Job? = null
    private var previewJob: Job? = null
    private val jobs = mutableListOf<Job>()
    private var openedAt = 0L
    private var firstPreviewLogged = false

    fun open(nowPlaying: NowPlaying) {
        if (mutableState.value.open) return
        openedAt = System.nanoTime()
        firstPreviewLogged = false
        mutableState.value = ShareUiState(open = true, nowPlaying = nowPlaying)
        val prefetch = container.lyrics
        // Normally already looked up; if not (the track has just changed), ask now.
        if (phaseFor(prefetch.state.value, nowPlaying.trackId) == null) {
            jobs += viewModelScope.launch { prefetch.request(nowPlaying) }
        }
        jobs += viewModelScope.launch {
            prefetch.state.collect { lyrics ->
                val phase = phaseFor(lyrics, nowPlaying.trackId) ?: return@collect
                val lines = ((lyrics as? LyricsState.Ready)?.lyrics as? Lyrics.Text)?.lines.orEmpty()
                mutableState.update { it.copy(phase = phase, lines = lines) }
                if (phase == SharePhase.CHOOSING) openSession(nowPlaying)
            }
        }
    }

    private fun openSession(nowPlaying: NowPlaying) {
        if (session != null || sessionJob?.isActive == true) return
        sessionJob = viewModelScope.launch {
            val opened = try {
                container.lyricsShare.open(nowPlaying)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No base to share from", e)
                mutableState.update { it.copy(phase = SharePhase.IMAGE_UNAVAILABLE) }
                return@launch
            }
            session = opened
            mutableState.update { it.copy(ready = true) }
            // The first verse, so the preview shows without another tap.
            val first = mutableState.value.lines.indexOfFirst { it.isNotBlank() }
            if (first >= 0) tap(first)
        }
    }

    fun tap(index: Int) {
        val current = session ?: return
        val state = mutableState.value
        val result = state.selection.tap(index, state.lines) { current.fits(it.of(state.lines)) }
        mutableState.update { it.copy(selection = result.selection, tooLong = result.refused, saveFailed = false) }
        if (result.selection != state.selection) redrawPreview(current)
    }

    private fun redrawPreview(current: ShareSession) {
        previewJob?.cancel()
        val selection = mutableState.value.selection
        if (selection == null) {
            mutableState.update { it.copy(preview = null, previewOf = null) }
            return
        }
        val lines = mutableState.value.lines
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DEBOUNCE_MS) // taps in a row draw once
            val image = current.draw(selection.of(lines))
            // Once per opening: clearing the selection and choosing again is not a first preview.
            if (!firstPreviewLogged) {
                firstPreviewLogged = true
                Log.d(TAG, "button to first preview: ${(System.nanoTime() - openedAt) / 1_000_000} ms")
            }
            mutableState.update { it.copy(preview = image, previewOf = selection) }
        }
    }

    fun share() {
        val current = session ?: return
        val state = mutableState.value
        val selection = state.selection ?: return
        if (state.saving) return
        mutableState.update { it.copy(saving = true, saveFailed = false) }
        jobs += viewModelScope.launch {
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

    fun retry() {
        val nowPlaying = mutableState.value.nowPlaying ?: return
        jobs += viewModelScope.launch { container.lyrics.request(nowPlaying) }
    }

    fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        sessionJob?.cancel()
        previewJob?.cancel()
        releaseSession()
        mutableState.value = ShareUiState()
    }

    override fun onCleared() = releaseSession()

    // After a draw in progress: the app scope outlives this model.
    private fun releaseSession() {
        val closing = session ?: return
        session = null
        container.appScope.launch { closing.close() }
    }

    private companion object {
        const val TAG = "LyricsShare"
        const val PREVIEW_DEBOUNCE_MS = 250L
    }
}
