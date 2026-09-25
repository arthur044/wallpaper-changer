package io.github.arthur044.wallpaperchanger.core.lyrics

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.IOException
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** What the lyrics lookup found for a track. */
sealed interface Lyrics {
    /** Plain (unsynced) lines; a blank string separates stanzas. Never empty. */
    data class Text(val lines: List<String>) : Lyrics

    /** The database knows the track has no words. */
    data object Instrumental : Lyrics

    /** Nobody transcribed it, or no candidate fits what is playing. */
    data object NotFound : Lyrics
}

/** The track to look up. [album] and [durationMs] may be unknown (the phone's session). */
data class LyricsQuery(val artist: String, val title: String, val album: String?, val durationMs: Long?) {
    companion object {
        /** Null when the track has no title or artist to ask about. */
        fun of(nowPlaying: NowPlaying): LyricsQuery? {
            val title = nowPlaying.trackName?.takeIf(String::isNotBlank) ?: return null
            val artist = nowPlaying.artistName?.takeIf(String::isNotBlank) ?: return null
            return LyricsQuery(artist, title, nowPlaying.albumName, nowPlaying.durationMs)
        }
    }
}

fun interface LyricsSource {
    /** @throws LyricsUnavailableException when the answer could not be had (no network, server trouble). */
    suspend fun lyrics(query: LyricsQuery): Lyrics
}

/** LRCLIB could not be asked, or answered something other than lyrics or "not found". */
class LyricsUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Lyrics from LRCLIB (https://lrclib.net, no account or key), matched as
 * spotifast does: an exact /get when album and duration are known, then a
 * ranked /search. It never goes through the Spotify ApiThrottle: 429 and the
 * backoff belong to the Spotify Web API.
 */
class LrclibClient(
    private val userAgent: String,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val http: OkHttpClient = defaultHttpClient(),
) : LyricsSource {

    override suspend fun lyrics(query: LyricsQuery): Lyrics {
        val title = cleanTitle(query.title)
        val artist = cleanArtist(query.artist)
        if (title.isBlank() || artist.isBlank()) return Lyrics.NotFound
        val album = query.album?.trim().orEmpty()
        val seconds = (query.durationMs ?: 0) / 1000
        // /get wants all four; without album or duration it can only say 400.
        if (album.isNotEmpty() && seconds > 0) {
            val exact = url("get", "artist_name" to artist, "track_name" to title, "album_name" to album, "duration" to "$seconds")
            get(exact, LrclibRecord.serializer())?.lyrics()?.let { return it }
        }
        val search = url("search", "artist_name" to artist, "track_name" to title)
        val candidates = get(search, ListSerializer(LrclibRecord.serializer())).orEmpty()
        return pick(candidates, query)?.lyrics() ?: Lyrics.NotFound
    }

    private fun url(path: String, vararg params: Pair<String, String>): HttpUrl =
        baseUrl.newBuilder().addPathSegment(path).apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()

    // A 404 is "nobody has transcribed this" and a 400 "not a question I can
    // answer"; neither is a fault worth showing.
    private suspend fun <T> get(url: HttpUrl, deserializer: DeserializationStrategy<T>): T? {
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        return try {
            http.newCall(request).executeAsync().use { response ->
                when {
                    response.code == HTTP_NOT_FOUND || response.code == HTTP_BAD_REQUEST -> null
                    !response.isSuccessful -> throw LyricsUnavailableException("LRCLIB answered HTTP ${response.code}")
                    else -> decode(deserializer, withContext(Dispatchers.IO) { response.body.string() })
                }
            }
        } catch (e: IOException) {
            throw LyricsUnavailableException("LRCLIB unreachable: ${e.message}", e)
        }
    }

    private fun <T> decode(deserializer: DeserializationStrategy<T>, body: String): T = try {
        json.decodeFromString(deserializer, body)
    } catch (e: IllegalArgumentException) { // includes kotlinx SerializationException
        throw LyricsUnavailableException("Unexpected LRCLIB response: ${e.message}", e)
    }

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://lrclib.net/api/".toHttpUrl()

        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_NOT_FOUND = 404

        internal val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10.seconds.toJavaDuration())
            .readTimeout(10.seconds.toJavaDuration())
            .callTimeout(20.seconds.toJavaDuration())
            .build()
    }
}

/** One LRCLIB track, as /get returns it and /search lists it. */
@Serializable
internal class LrclibRecord(
    val id: Long? = null,
    val trackName: String = "",
    val artistName: String = "",
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
) {
    val synced: String? get() = syncedLyrics?.takeIf(String::isNotBlank)
    val plain: String? get() = plainLyrics?.takeIf(String::isNotBlank)

    /** Null when the record holds no words and isn't marked instrumental. */
    fun lyrics(): Lyrics? {
        if (instrumental) return Lyrics.Instrumental
        // The share is plain text: the plain upload first, the synced one's
        // words when it is all there is.
        val lines = plain?.lines() ?: synced?.let(::lrcText) ?: return null
        return tidy(lines).takeIf { it.isNotEmpty() }?.let(Lyrics::Text)
    }
}

/** A candidate this far from the playing track's length is another recording. */
private const val MAX_DRIFT_SECS = 30.0

/** How well a search result fits what is playing; null rules it out. */
internal fun score(record: LrclibRecord, query: LyricsQuery): Int? {
    if (!looseMatch(record.trackName, cleanTitle(query.title))) return null
    var score = 0
    if (looseMatch(record.artistName, cleanArtist(query.artist))) score += 1000
    val durationMs = query.durationMs ?: 0
    val duration = record.duration?.takeIf { it > 0 }
    if (durationMs > 0 && duration != null) {
        val drift = abs(duration - durationMs / 1000.0)
        if (drift > MAX_DRIFT_SECS) return null
        score += ((MAX_DRIFT_SECS - drift) * 10).toInt()
    }
    // A synced upload is usually the more careful one, so it wins a tie.
    score += when {
        record.synced != null -> 200
        record.plain != null -> 50
        else -> 0
    }
    return score
}

/** The best-scoring candidate; the first one wins a tie. */
internal fun pick(records: List<LrclibRecord>, query: LyricsQuery): LrclibRecord? {
    var best: Pair<Int, LrclibRecord>? = null
    for (record in records) {
        val score = score(record, query) ?: continue
        if (best == null || score > best.first) best = score to record
    }
    return best?.second
}

/** Trailing spaces gone, one blank line between stanzas, none at either end. */
internal fun tidy(lines: List<String>): List<String> {
    val out = mutableListOf<String>()
    for (line in lines.map(String::trimEnd)) {
        if (line.isBlank() && (out.isEmpty() || out.last().isEmpty())) continue
        out += if (line.isBlank()) "" else line
    }
    if (out.lastOrNull()?.isEmpty() == true) out.removeAt(out.lastIndex)
    return out
}
