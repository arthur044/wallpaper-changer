package io.github.arthur044.wallpaperchanger.core.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.IOException

/**
 * Downloads album art (renderer.download_art). Bounded by [maxBytes] so a bad
 * URL can't make us buffer something huge; every failure is transient, so the
 * sync loop just retries on a later cycle.
 *
 * Only Spotify's image CDN is fetched ([isSpotifyArtUrl]), checked again after
 * redirects: the bytes go to the platform image decoder, so a forged API answer
 * must not be able to point it at an arbitrary host.
 */
class ArtDownloader(
    private val http: OkHttpClient = SpotifyApi.defaultHttpClient(),
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val isAllowed: (HttpUrl) -> Boolean = ::isSpotifyArtUrl,
) : ArtSource {
    override suspend fun download(url: String): ByteArray {
        val httpUrl = url.toHttpUrlOrNull() ?: throw TransientNetworkException("Invalid art URL: $url")
        if (!isAllowed(httpUrl)) throw notAllowed(httpUrl)
        val response = try {
            http.newCall(Request.Builder().url(httpUrl).build()).executeAsync()
        } catch (e: IOException) {
            throw TransientNetworkException("Art download failed: ${e.message}", e)
        }
        return response.use { r ->
            if (!isAllowed(r.request.url)) throw notAllowed(r.request.url)
            if (!r.isSuccessful) throw TransientNetworkException("Art download failed: HTTP ${r.code}")
            if (r.body.contentLength() > maxBytes) throw tooLarge(r.body.contentLength())
            try {
                withContext(Dispatchers.IO) {
                    val source = r.body.source()
                    // Buffers at most maxBytes + 1: enough to tell "too big" apart.
                    if (source.request(maxBytes + 1)) throw tooLarge(source.buffer.size)
                    source.readByteArray()
                }
            } catch (e: IOException) {
                throw TransientNetworkException("Art download cut off: ${e.message}", e)
            }
        }
    }

    private fun tooLarge(size: Long) = TransientNetworkException("Art is too large ($size bytes, limit $maxBytes)")

    private fun notAllowed(url: HttpUrl) =
        TransientNetworkException("Art URL outside Spotify's CDN: ${url.scheme}://${url.host}")

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 10L * 1024 * 1024
    }
}

private val SPOTIFY_ART_DOMAINS = listOf("scdn.co", "spotifycdn.com")

/** HTTPS on scdn.co / spotifycdn.com or a subdomain of them (album art is on i.scdn.co). */
fun isSpotifyArtUrl(url: HttpUrl): Boolean =
    url.isHttps && SPOTIFY_ART_DOMAINS.any { url.host == it || url.host.endsWith(".$it") }
