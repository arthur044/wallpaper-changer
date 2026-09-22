package io.github.arthur044.wallpaperchanger.core.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.IOException

/**
 * Downloads album art (renderer.download_art). Bounded by [maxBytes] so a bad
 * URL can't make us buffer something huge; every failure is transient, so the
 * sync loop just retries on a later cycle.
 */
class ArtDownloader(
    private val http: OkHttpClient = SpotifyApi.defaultHttpClient(),
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : ArtSource {
    override suspend fun download(url: String): ByteArray {
        val httpUrl = url.toHttpUrlOrNull() ?: throw TransientNetworkException("Invalid art URL: $url")
        val response = try {
            http.newCall(Request.Builder().url(httpUrl).build()).executeAsync()
        } catch (e: IOException) {
            throw TransientNetworkException("Art download failed: ${e.message}", e)
        }
        return response.use { r ->
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

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 10L * 1024 * 1024
    }
}
