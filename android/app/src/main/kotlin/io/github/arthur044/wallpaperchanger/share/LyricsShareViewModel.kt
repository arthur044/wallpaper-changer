package io.github.arthur044.wallpaperchanger.share

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.arthur044.wallpaperchanger.WallpaperApp
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import kotlinx.coroutines.flow.StateFlow

/**
 * The share screen's state, per activity: rotation, folding and a zoom
 * (density) change keep the lyrics, the selection and the image drawn; none
 * is looked up or drawn again. The logic is [ShareScreenModel]; this only ties
 * it to the app's lyrics lookup and share images.
 */
class LyricsShareViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as WallpaperApp).container
    private val model = ShareScreenModel(
        scope = viewModelScope,
        releaseScope = container.appScope,
        lyricsOf = container.lyrics::watch,
        openDrawing = container.lyricsShare::open,
    )

    val state: StateFlow<ShareUiState> = model.state

    fun open(nowPlaying: NowPlaying) = model.open(nowPlaying)

    fun tap(index: Int) = model.tap(index)

    fun share() = model.share()

    fun onShareSheetShown() = model.onShareSheetShown()

    fun onShareSheetFailed() = model.onShareSheetFailed()

    fun retry() = model.retry()

    fun close() = model.close()

    override fun onCleared() = model.release()
}
