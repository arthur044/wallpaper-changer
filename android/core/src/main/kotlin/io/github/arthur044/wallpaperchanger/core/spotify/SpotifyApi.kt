package io.github.arthur044.wallpaperchanger.core.spotify

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

data class AlbumTrack(val name: String, val artistName: String)

/**
 * The two Spotify Web API calls the sync loop needs, with client.py's error
 * split: [RateLimitedException] (429), [AuthExpiredException] (401 that
 * survives a token refresh), [TransientNetworkException] (everything else).
 */
class SpotifyApi(
    private val tokens: AccessTokenProvider,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    /** What the account is playing on any device, or null when there's nothing to show. */
    suspend fun currentlyPlaying(): NowPlaying? {
        val body = get(baseUrl.newBuilder().addPathSegments("me/player/currently-playing").build()) ?: return null
        val payload = decode(CurrentlyPlayingDto.serializer(), body)
        // Ads and podcast episodes have no track item.
        val item = payload.item ?: return null
        return NowPlaying(
            isPlaying = payload.isPlaying,
            trackId = item.id,
            albumId = item.album?.id,
            // Spotify lists images widest first; the first is the high-res art.
            artUrl = item.album?.images?.firstNotNullOfOrNull { it.url },
            trackName = item.name,
            artistName = item.artists.joinNames().ifEmpty { null },
        )
    }

    /**
     * Every named track on the album's first page (up to 50). Longer box sets
     * just get their later tracks resolved individually, as on desktop.
     */
    suspend fun albumTracks(albumId: String): List<AlbumTrack> {
        val url = baseUrl.newBuilder()
            .addPathSegment("albums").addPathSegment(albumId).addPathSegment("tracks")
            .addQueryParameter("limit", "50")
            .build()
        val body = get(url) ?: return emptyList()
        return decode(AlbumTracksDto.serializer(), body).items.mapNotNull { track ->
            track.name?.let { AlbumTrack(it, track.artists.joinNames()) }
        }
    }

    // Response body, or null for 204 / an empty 200.
    private suspend fun get(url: HttpUrl): String? {
        val first = send(url, tokens.accessToken())
        val response = if (first.code == HTTP_UNAUTHORIZED) {
            // The token looked valid but was refused: refresh once and retry.
            first.close()
            send(url, tokens.accessToken(forceRefresh = true))
        } else {
            first
        }
        return response.use { r ->
            when {
                r.code == HTTP_NO_CONTENT -> null
                r.isSuccessful -> readBody(r).ifBlank { null }
                r.code == HTTP_TOO_MANY_REQUESTS -> throw RateLimitedException(parseRetryAfter(r.header("Retry-After")))
                r.code == HTTP_UNAUTHORIZED -> throw AuthExpiredException("Spotify refused a freshly refreshed token (401)")
                else -> throw TransientNetworkException("Spotify Web API returned HTTP ${r.code} for ${url.encodedPath}")
            }
        }
    }

    private suspend fun send(url: HttpUrl, token: String): Response = try {
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).executeAsync()
    } catch (e: IOException) {
        throw TransientNetworkException("Spotify Web API unreachable: ${e.message}", e)
    }

    private suspend fun readBody(response: Response): String = try {
        withContext(Dispatchers.IO) { response.body.string() }
    } catch (e: IOException) {
        throw TransientNetworkException("Spotify Web API response cut off: ${e.message}", e)
    }

    private fun <T> decode(deserializer: DeserializationStrategy<T>, body: String): T = try {
        json.decodeFromString(deserializer, body)
    } catch (e: IllegalArgumentException) { // includes kotlinx SerializationException
        throw TransientNetworkException("Unexpected Spotify Web API response: ${e.message}", e)
    }

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://api.spotify.com/v1/".toHttpUrl()
        private val DEFAULT_RETRY_AFTER = 5.seconds

        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10.seconds.toJavaDuration())
            .readTimeout(10.seconds.toJavaDuration())
            .callTimeout(20.seconds.toJavaDuration())
            .build()

        private fun parseRetryAfter(header: String?): Duration =
            header?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }?.seconds ?: DEFAULT_RETRY_AFTER
    }
}

private fun List<ArtistDto>.joinNames(): String =
    mapNotNull { it.name?.takeIf(String::isNotBlank) }.joinToString(", ")

@Serializable
private class CurrentlyPlayingDto(
    @SerialName("is_playing") val isPlaying: Boolean = false,
    val item: TrackDto? = null,
)

@Serializable
private class TrackDto(
    val id: String? = null,
    val name: String? = null,
    val artists: List<ArtistDto> = emptyList(),
    val album: AlbumDto? = null,
)

@Serializable
private class ArtistDto(val name: String? = null)

@Serializable
private class AlbumDto(val id: String? = null, val images: List<ImageDto> = emptyList())

@Serializable
private class ImageDto(val url: String? = null)

@Serializable
private class AlbumTracksDto(val items: List<SimpleTrackDto> = emptyList())

@Serializable
private class SimpleTrackDto(val name: String? = null, val artists: List<ArtistDto> = emptyList())
