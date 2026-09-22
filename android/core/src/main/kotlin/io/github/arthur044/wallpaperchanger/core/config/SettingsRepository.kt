package io.github.arthur044.wallpaperchanger.core.config

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Single source of truth for [Settings]; every write is sanitized first. */
class SettingsRepository internal constructor(private val store: DataStore<Settings>) {
    val settings: Flow<Settings> = store.data

    /** Applies [transform] atomically and returns what was actually stored. */
    suspend fun update(transform: (Settings) -> Settings): Settings =
        store.updateData { transform(it).sanitized() }

    companion object {
        /**
         * Only one repository may be open per [file] at a time (a DataStore rule).
         * [onCorruption] is told about an unreadable file before it is replaced
         * with defaults, so the failure gets logged instead of vanishing.
         */
        fun create(
            file: File,
            scope: CoroutineScope,
            onCorruption: (Throwable) -> Unit = {},
        ): SettingsRepository = SettingsRepository(
            DataStoreFactory.create(
                serializer = SettingsSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { error ->
                    onCorruption(error)
                    SettingsSerializer.defaultValue
                },
                scope = scope,
                produceFile = { file },
            ),
        )
    }
}
