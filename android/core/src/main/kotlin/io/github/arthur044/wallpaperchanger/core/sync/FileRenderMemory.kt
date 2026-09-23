package io.github.arthur044.wallpaperchanger.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [RenderMemory] in a one-line file (the track id; empty = none), written
 * atomically by DataStore. Disk errors are reported and otherwise ignored:
 * forgetting only costs one redraw, it must never stop the sync.
 */
class FileRenderMemory private constructor(
    private val store: DataStore<String>,
    private val onError: (Throwable) -> Unit,
) : RenderMemory {

    override suspend fun lastRenderedTrackId(): String? = try {
        store.data.first().ifEmpty { null }
    } catch (e: IOException) {
        onError(e)
        null
    }

    override suspend fun remember(trackId: String?) {
        try {
            store.updateData { trackId.orEmpty() }
        } catch (e: IOException) {
            onError(e)
        }
    }

    companion object {
        /** Only one instance may be open per [file] at a time (a DataStore rule). */
        fun create(file: File, scope: CoroutineScope, onError: (Throwable) -> Unit = {}): FileRenderMemory =
            FileRenderMemory(
                DataStoreFactory.create(serializer = TrackIdSerializer, scope = scope, produceFile = { file }),
                onError,
            )
    }
}

private object TrackIdSerializer : Serializer<String> {
    override val defaultValue: String = ""

    override suspend fun readFrom(input: InputStream): String = input.readBytes().decodeToString().trim()

    override suspend fun writeTo(t: String, output: OutputStream) {
        output.write(t.encodeToByteArray())
    }
}
