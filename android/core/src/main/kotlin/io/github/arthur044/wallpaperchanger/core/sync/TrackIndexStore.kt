package io.github.arthur044.wallpaperchanger.core.sync

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import io.github.arthur044.wallpaperchanger.core.ResolvedAlbum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Where the track -> album index is kept between runs. */
interface TrackIndexStore {
    /** Saved entries, least recently used first; empty when there are none. */
    suspend fun load(): List<Pair<String, ResolvedAlbum>>

    suspend fun save(entries: List<Pair<String, ResolvedAlbum>>)

    companion object {
        /** Memory only: nothing survives the process. */
        val None: TrackIndexStore = object : TrackIndexStore {
            override suspend fun load(): List<Pair<String, ResolvedAlbum>> = emptyList()

            override suspend fun save(entries: List<Pair<String, ResolvedAlbum>>) = Unit
        }
    }
}

/**
 * [TrackIndexStore] in a JSON file, written atomically by DataStore. Disk
 * errors and unreadable files are reported and otherwise ignored: losing the
 * index only costs one lookup per album, it must never stop the sync.
 */
class FileTrackIndexStore private constructor(
    private val store: DataStore<StoredIndex>,
    private val onError: (Throwable) -> Unit,
) : TrackIndexStore {

    override suspend fun load(): List<Pair<String, ResolvedAlbum>> = try {
        store.data.first().takeIf { it.version == VERSION }?.tracks.orEmpty()
            .map { it.key to ResolvedAlbum(it.albumId, it.artUrl) }
    } catch (e: IOException) {
        onError(e)
        emptyList()
    }

    override suspend fun save(entries: List<Pair<String, ResolvedAlbum>>) {
        try {
            store.updateData { StoredIndex(VERSION, entries.map { (key, album) -> StoredEntry(key, album.albumId, album.artUrl) }) }
        } catch (e: IOException) {
            onError(e)
        }
    }

    companion object {
        /** Only one instance may be open per [file] at a time (a DataStore rule). */
        fun create(file: File, scope: CoroutineScope, onError: (Throwable) -> Unit = {}): FileTrackIndexStore =
            FileTrackIndexStore(
                DataStoreFactory.create(
                    serializer = IndexSerializer,
                    corruptionHandler = ReplaceFileCorruptionHandler { onError(it); StoredIndex() },
                    scope = scope,
                    produceFile = { file },
                ),
                onError,
            )
    }
}

private const val VERSION = 1

@Serializable
internal data class StoredEntry(val key: String, val albumId: String, val artUrl: String? = null)

@Serializable
internal data class StoredIndex(val version: Int = VERSION, val tracks: List<StoredEntry> = emptyList())

private object IndexSerializer : Serializer<StoredIndex> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: StoredIndex = StoredIndex()

    override suspend fun readFrom(input: InputStream): StoredIndex = try {
        json.decodeFromString(StoredIndex.serializer(), input.readBytes().decodeToString())
    } catch (e: IllegalArgumentException) { // includes kotlinx SerializationException
        throw CorruptionException("Unreadable track index", e)
    }

    override suspend fun writeTo(t: StoredIndex, output: OutputStream) {
        output.write(json.encodeToString(StoredIndex.serializer(), t).encodeToByteArray())
    }
}
