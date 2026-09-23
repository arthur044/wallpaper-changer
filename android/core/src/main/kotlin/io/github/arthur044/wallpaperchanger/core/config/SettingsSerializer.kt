package io.github.arthur044.wallpaperchanger.core.config

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * JSON on disk. Missing keys and nulls take their defaults, unknown keys are
 * ignored, and out-of-range values are clamped. Anything unparseable (bad JSON,
 * a wrongly typed value) surfaces as [CorruptionException] so the repository's
 * corruption handler can report it and start over from defaults.
 */
object SettingsSerializer : Serializer<Settings> {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }

    override val defaultValue: Settings = Settings()

    override suspend fun readFrom(input: InputStream): Settings = try {
        json.decodeFromString(Settings.serializer(), input.readBytes().decodeToString()).sanitized()
    } catch (e: IllegalArgumentException) { // includes kotlinx SerializationException
        throw CorruptionException("Unreadable settings file", e)
    }

    override suspend fun writeTo(t: Settings, output: OutputStream) {
        output.write(json.encodeToString(Settings.serializer(), t).encodeToByteArray())
    }
}
