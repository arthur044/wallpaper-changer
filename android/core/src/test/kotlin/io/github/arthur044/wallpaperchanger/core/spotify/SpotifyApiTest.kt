package io.github.arthur044.wallpaperchanger.core.spotify

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

// Behavior of client.py's fetch_now_playing / fetch_album_tracks and their
// error mapping, against a local HTTP server.
class SpotifyApiTest {
    private val server = MockWebServer()
    private val tokens = FakeTokens()
    private lateinit var api: SpotifyApi

    @BeforeEach
    fun start() {
        server.start()
        api = SpotifyApi(tokens, baseUrl = server.url("/v1/"))
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun respond(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).body(body)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        server.enqueue(builder.build())
    }

    // --- currentlyPlaying ---------------------------------------------------

    @Test
    fun `parses a playing track`() = runTest {
        respond(200, PLAYING_JSON)

        assertEquals(
            NowPlaying(
                isPlaying = true,
                trackId = "t1",
                albumId = "a1",
                artUrl = "https://i.scdn.co/image/640",
                trackName = "Airbag",
                artistName = "Radiohead, Guest",
            ),
            api.currentlyPlaying(),
        )
    }

    @Test
    fun `calls the currently-playing endpoint with the bearer token`() = runTest {
        respond(204)
        api.currentlyPlaying()

        val request = server.takeRequest()
        assertEquals("/v1/me/player/currently-playing", request.url.encodedPath)
        assertEquals("Bearer stale", request.headers["Authorization"])
    }

    @Test
    fun `no content means nothing is playing`() = runTest {
        respond(204)
        assertNull(api.currentlyPlaying())
    }

    @Test
    fun `a payload without a track item means nothing to show`() = runTest {
        // Ads and podcast episodes come back with "item": null.
        respond(200, """{"is_playing": true, "currently_playing_type": "ad", "item": null}""")
        assertNull(api.currentlyPlaying())
    }

    @Test
    fun `an album without images has no art url`() = runTest {
        respond(200, PLAYING_JSON.replace(Regex(""""images": \[[^\]]*]"""), """"images": []"""))
        assertNull(api.currentlyPlaying()?.artUrl)
    }

    @Test
    fun `429 carries the retry-after delay`() = runTest {
        respond(429, headers = arrayOf("Retry-After" to "17"))
        assertEquals(17.seconds, assertThrows<RateLimitedException> { api.currentlyPlaying() }.retryAfter)
    }

    @Test
    fun `429 without retry-after defaults to five seconds`() = runTest {
        respond(429)
        assertEquals(5.seconds, assertThrows<RateLimitedException> { api.currentlyPlaying() }.retryAfter)
    }

    @Test
    fun `401 refreshes the token once and retries`() = runTest {
        respond(401)
        respond(200, PLAYING_JSON)

        assertEquals("t1", api.currentlyPlaying()?.trackId)
        assertEquals(listOf(false, true), tokens.forceRefreshCalls)
        server.takeRequest()
        assertEquals("Bearer fresh", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `401 again after a refresh means the session is gone`() = runTest {
        respond(401)
        respond(401)
        assertThrows<AuthExpiredException> { api.currentlyPlaying() }
    }

    @Test
    fun `server errors are transient`() = runTest {
        respond(503)
        assertThrows<TransientNetworkException> { api.currentlyPlaying() }
    }

    @Test
    fun `a malformed body is transient`() = runTest {
        respond(200, "{ not json")
        assertThrows<TransientNetworkException> { api.currentlyPlaying() }
    }

    @Test
    fun `an unreachable server is transient`() = runTest {
        server.close()
        assertThrows<TransientNetworkException> { api.currentlyPlaying() }
    }

    @Test
    fun `token failures propagate without calling the api`() = runTest {
        tokens.failWith = AuthExpiredException("Not signed in")
        assertThrows<AuthExpiredException> { api.currentlyPlaying() }
        assertEquals(0, server.requestCount)
    }

    // --- albumTracks ------------------------------------------------------

    @Test
    fun `lists an album's tracks from its first page`() = runTest {
        respond(200, ALBUM_TRACKS_JSON)

        assertEquals(
            listOf(AlbumTrack("Airbag", "Radiohead"), AlbumTrack("Lucky", "Radiohead, Guest")),
            api.albumTracks("a1"),
        )
        val request = server.takeRequest()
        assertEquals("/v1/albums/a1/tracks", request.url.encodedPath)
        assertEquals("50", request.url.queryParameter("limit"))
    }

    @Test
    fun `album tracks share the same error mapping`() = runTest {
        respond(429, headers = arrayOf("Retry-After" to "3"))
        assertEquals(3.seconds, assertThrows<RateLimitedException> { api.albumTracks("a1") }.retryAfter)
    }

    private class FakeTokens : AccessTokenProvider {
        val forceRefreshCalls = mutableListOf<Boolean>()
        var failWith: Exception? = null

        override suspend fun accessToken(forceRefresh: Boolean): String {
            forceRefreshCalls += forceRefresh
            failWith?.let { throw it }
            return if (forceRefresh) "fresh" else "stale"
        }
    }

    private companion object {
        val PLAYING_JSON = """
            {
              "is_playing": true,
              "currently_playing_type": "track",
              "progress_ms": 1000,
              "item": {
                "id": "t1",
                "name": "Airbag",
                "type": "track",
                "artists": [{"name": "Radiohead"}, {"name": "Guest"}],
                "album": {
                  "id": "a1",
                  "name": "OK Computer",
                  "images": [
                    {"url": "https://i.scdn.co/image/640", "width": 640, "height": 640},
                    {"url": "https://i.scdn.co/image/300", "width": 300, "height": 300}
                  ]
                }
              }
            }
        """.trimIndent()

        val ALBUM_TRACKS_JSON = """
            {
              "items": [
                {"name": "Airbag", "artists": [{"name": "Radiohead"}]},
                {"name": null, "artists": []},
                {"name": "Lucky", "artists": [{"name": "Radiohead"}, {"name": "Guest"}]}
              ],
              "next": null
            }
        """.trimIndent()
    }
}
