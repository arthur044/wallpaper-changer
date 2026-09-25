package io.github.arthur044.wallpaperchanger.core.lyrics

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/** Where the lyrics of the track on screen stand. */
sealed interface LyricsState {
    /** No track has been on screen while the screen followed. */
    data object None : LyricsState

    data class Loading(val trackId: String?) : LyricsState

    data class Ready(val trackId: String?, val lyrics: Lyrics) : LyricsState

    /** The lookup failed (no network, LRCLIB trouble); asking again may work. */
    data class Unavailable(val trackId: String?) : LyricsState
}

/**
 * Looks the lyrics up before the share button is pressed, so they are ready
 * when it is. It asks only while [follow] runs, and only the main screen runs
 * it, while it is visible: never the sync service, never in the background.
 *
 * A pause (Idle) keeps the answer, since the wallpaper still shows that track;
 * a new track cancels a lookup still out for the old one.
 */
class LyricsPrefetch(private val slot: LyricsSlot) {
    private val mutableState = MutableStateFlow<LyricsState>(LyricsState.None)
    val state: StateFlow<LyricsState> = mutableState.asStateFlow()

    /** Follows [status] until cancelled (the screen stopped). */
    suspend fun follow(status: Flow<SyncStatus>) {
        status.filterIsInstance<SyncStatus.Showing>()
            .map { it.nowPlaying }
            // The phone's session may report the same track again with details
            // it lacked (duration, album): that is not a new track.
            .distinctUntilChangedBy { it.trackId }
            .collectLatest(::lookUp)
    }

    private suspend fun lookUp(nowPlaying: NowPlaying) {
        val trackId = nowPlaying.trackId
        val known = trackId?.let(slot::peek)
        if (known != null) {
            mutableState.value = LyricsState.Ready(trackId, known)
            return
        }
        mutableState.value = LyricsState.Loading(trackId)
        mutableState.value = try {
            LyricsState.Ready(trackId, slot.lyricsFor(nowPlaying))
        } catch (e: LyricsUnavailableException) {
            LyricsState.Unavailable(trackId)
        }
    }
}
