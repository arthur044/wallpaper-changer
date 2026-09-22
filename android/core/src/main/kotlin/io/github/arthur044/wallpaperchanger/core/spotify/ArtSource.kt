package io.github.arthur044.wallpaperchanger.core.spotify

/** Where album art bytes come from; faked in tests to count downloads. */
fun interface ArtSource {
    /** @throws TransientNetworkException when the art can't be fetched right now. */
    suspend fun download(url: String): ByteArray
}
