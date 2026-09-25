package io.github.arthur044.wallpaperchanger.core.lyrics

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// Against saved LRCLIB answers (src/test/resources/lrclib): real field layout,
// titles and durations, the words replaced by placeholder lines.
class LrclibClientTest {
    private val server = MockWebServer()
    private lateinit var client: LrclibClient

    private val metropolis = LyricsQuery(
        artist = "Dream Theater",
        title = "Metropolis - Part I: \"The Miracle and the Sleeper\"",
        album = "Images and Words",
        durationMs = 571_000,
    )
    private val yyz = LyricsQuery("Rush", "YYZ", "Moving Pictures", 266_000)
    private val placeholders = Lyrics.Text(listOf("Placeholder line 1", "Placeholder line 2", "Placeholder line 3"))

    @BeforeEach
    fun start() {
        server.start()
        client = LrclibClient("WallpaperChanger-test", baseUrl = server.url("/api/"))
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/lrclib/$name")).readText()

    private fun respond(code: Int, body: String = "") {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    @Test
    fun `the exact lookup sends all four fields, quotes and colon intact`() = runTest {
        respond(200, fixture("get_metropolis.json"))

        assertEquals(placeholders, client.lyrics(metropolis))

        val request = server.takeRequest()
        assertEquals("/api/get", request.url.encodedPath)
        assertEquals("Dream Theater", request.url.queryParameter("artist_name"))
        assertEquals("Metropolis - Part I: \"The Miracle and the Sleeper\"", request.url.queryParameter("track_name"))
        assertEquals("Images and Words", request.url.queryParameter("album_name"))
        assertEquals("571", request.url.queryParameter("duration"))
        assertEquals("WallpaperChanger-test", request.headers["User-Agent"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a miss on the exact lookup falls back to the search`() = runTest {
        respond(404, fixture("not_found.json"))
        respond(200, fixture("search_metropolis.json"))

        assertEquals(placeholders, client.lyrics(metropolis))

        assertEquals("/api/get", server.takeRequest().url.encodedPath)
        val search = server.takeRequest()
        assertEquals("/api/search", search.url.encodedPath)
        assertEquals("Dream Theater", search.url.queryParameter("artist_name"))
        assertEquals(metropolis.title, search.url.queryParameter("track_name"))
        assertNull(search.url.queryParameter("duration"))
    }

    @Test
    fun `a bad request on the exact lookup also falls back to the search`() = runTest {
        respond(400)
        respond(200, fixture("search_metropolis.json"))

        assertEquals(placeholders, client.lyrics(metropolis))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an exact hit without words falls back to the search`() = runTest {
        respond(200, """{"trackName": "Metropolis", "artistName": "Dream Theater", "instrumental": false}""")
        respond(200, fixture("search_metropolis.json"))

        assertEquals(placeholders, client.lyrics(metropolis))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `without a duration the lookup goes straight to the search`() = runTest {
        respond(200, fixture("search_metropolis.json"))

        assertEquals(placeholders, client.lyrics(metropolis.copy(durationMs = null)))
        assertEquals("/api/search", server.takeRequest().url.encodedPath)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `without an album the lookup goes straight to the search`() = runTest {
        respond(200, fixture("search_metropolis.json"))

        assertEquals(placeholders, client.lyrics(metropolis.copy(album = " ")))
        assertEquals("/api/search", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `nobody has it means no lyrics`() = runTest {
        respond(404, fixture("not_found.json"))
        respond(404, fixture("not_found.json"))

        assertEquals(Lyrics.NotFound, client.lyrics(metropolis))
    }

    @Test
    fun `a search with no fitting candidate means no lyrics`() = runTest {
        respond(404)
        respond(200, "[]")

        assertEquals(Lyrics.NotFound, client.lyrics(metropolis))
    }

    @Test
    fun `an instrumental is reported as such`() = runTest {
        respond(404, fixture("not_found.json"))
        respond(200, fixture("search_yyz_instrumental.json"))

        assertEquals(Lyrics.Instrumental, client.lyrics(yyz))
    }

    @Test
    fun `a server error is not the same as no lyrics`() = runTest {
        respond(503)

        assertThrows<LyricsUnavailableException> { client.lyrics(metropolis) }
    }

    @Test
    fun `an answer that isn't LRCLIB's is not the same as no lyrics`() = runTest {
        respond(200, "<html>captive portal</html>")

        assertThrows<LyricsUnavailableException> { client.lyrics(metropolis) }
    }

    @Test
    fun `no network is not the same as no lyrics`() = runTest {
        server.close()

        assertThrows<LyricsUnavailableException> { client.lyrics(metropolis) }
    }

    // --- ranking --------------------------------------------------------------

    private fun searchFixture(name: String): List<LrclibRecord> =
        LrclibClient.json.decodeFromString(ListSerializer(LrclibRecord.serializer()), fixture(name))

    @Test
    fun `the search picks the recording of the same length, not a live or cut version`() {
        // The fixture holds 774 s and 636 s live cuts, a 233 s game edit and
        // several 572 s uploads; the 571 s one is the album track.
        assertEquals(34660130L, pick(searchFixture("search_metropolis.json"), metropolis)?.id)
    }

    @Test
    fun `a candidate more than 30 s off is another recording`() {
        val tooLong = LrclibRecord(trackName = "YYZ", artistName = "Rush", duration = 297.0, plainLyrics = "x")
        assertNull(pick(listOf(tooLong), yyz))
        assertEquals(tooLong, pick(listOf(tooLong), yyz.copy(durationMs = null)), "no duration, no drift check")
    }

    @Test
    fun `the closest length wins and synced breaks ties`() {
        val query = LyricsQuery("Artist", "Song", null, 200_000)
        fun record(track: String, duration: Double, synced: Boolean) = LrclibRecord(
            trackName = track,
            artistName = "Artist",
            duration = duration,
            plainLyrics = "a",
            syncedLyrics = if (synced) "[00:01.00] a" else null,
        )
        val picked = pick(
            listOf(record("Song", 260.0, true), record("Song", 201.0, false), record("Song", 202.0, true), record("Other", 200.0, true)),
            query,
        )
        assertEquals(202.0, picked?.duration)
    }

    @Test
    fun `the right artist outweighs a closer length`() {
        val query = LyricsQuery("Artist", "Song", null, 200_000)
        val cover = LrclibRecord(trackName = "Song", artistName = "Someone Else", duration = 200.0, syncedLyrics = "[00:01]a")
        val original = LrclibRecord(trackName = "Song", artistName = "Artist", duration = 225.0, plainLyrics = "a")
        assertEquals(original, pick(listOf(cover, original), query))
    }

    @Test
    fun `synced-only words are used when there is no plain upload`() {
        val record = LrclibRecord(syncedLyrics = "[00:10.00] Second\n[00:05.00] First\n[00:15.00]\n[00:20.00] Third")
        assertEquals(Lyrics.Text(listOf("First", "Second", "", "Third")), record.lyrics())
    }

    @Test
    fun `a query needs a title and an artist`() {
        val playing = NowPlaying(true, "t1", "a1", null, "Airbag", "Radiohead", "OK Computer", 287_000)
        assertEquals(LyricsQuery("Radiohead", "Airbag", "OK Computer", 287_000), LyricsQuery.of(playing))
        assertNull(LyricsQuery.of(playing.copy(trackName = " ")))
        assertNull(LyricsQuery.of(playing.copy(artistName = null)))
    }
}
