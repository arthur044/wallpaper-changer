package io.github.arthur044.wallpaperchanger.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** What Spotify's own session says is playing on this phone, no network involved. */
data class MediaSnapshot(
    val title: String?,
    val artist: String?,
    val album: String?,
    val isPlaying: Boolean,
    val atMillis: Long,
)

const val SPOTIFY_PACKAGE = "com.spotify.music"

/**
 * TEMPORARY (M13 spike): Spotify's MediaSession, as a flow of snapshots.
 * Requires notification access ([notificationAccessGranted]); without it
 * MediaSessionManager returns no sessions at all.
 */
class MediaSessionProbe(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {

    fun snapshots(): Flow<MediaSnapshot> = callbackFlow {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        var watched: MediaController? = null
        var watcher: MediaController.Callback? = null

        fun emit(controller: MediaController) {
            val metadata = controller.metadata ?: return
            trySend(
                MediaSnapshot(
                    title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                    artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
                    atMillis = now(),
                ),
            )
        }

        fun watch(controllers: List<MediaController>) {
            val spotify = controllers.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
            if (spotify?.sessionToken == watched?.sessionToken) return
            watcher?.let { watched?.unregisterCallback(it) }
            watched = spotify
            watcher = null
            if (spotify == null) return
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = emit(spotify)

                override fun onPlaybackStateChanged(state: PlaybackState?) = emit(spotify)

                override fun onSessionDestroyed() = watch(emptyList())
            }
            spotify.registerCallback(callback, handler)
            watcher = callback
            emit(spotify) // whatever is already playing
        }

        val component = context.mediaListenerComponent()
        val onSessions = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            watch(controllers.orEmpty())
        }
        manager.addOnActiveSessionsChangedListener(onSessions, component, handler)
        // Everything that touches `watched`/`watcher` runs on the main looper,
        // including this first look: the session-changed callback arrives there,
        // and a second writer would leak a registered callback nobody unregisters.
        handler.post { watch(manager.getActiveSessions(component)) }

        awaitClose {
            manager.removeOnActiveSessionsChangedListener(onSessions)
            handler.post { watcher?.let { watched?.unregisterCallback(it) } }
        }
    }
}
