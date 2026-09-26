package io.github.arthur044.wallpaperchanger.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.render.PixelRect
import io.github.arthur044.wallpaperchanger.core.spotify.ArtSource
import io.github.arthur044.wallpaperchanger.core.sync.TrackNotDrawableException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

// render_for_now_playing's contract: a new album downloads and draws a base;
// another track on a cached album only redraws the text.
@RunWith(AndroidJUnit4::class)
class WallpaperComposerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "test_composer_bases")
    private val phone = CanvasSpec(1080, 2400, PixelRect(0, 63, 1080, 2274), density = 2.625f)
    private val art = CountingArtSource()
    private val composer = WallpaperComposer(art, WallpaperRenderer(), AlbumBaseCache(dir))

    private val airbag = NowPlaying(true, "t1", "a1", "https://i.scdn.co/image/a1", "Airbag", "Radiohead")
    private val lucky = airbag.copy(trackId = "t2", trackName = "Lucky")
    private val otherAlbum = airbag.copy(trackId = "t9", albumId = "a2", artUrl = "https://i.scdn.co/image/a2")

    @Before
    @After
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun firstTrackOfAnAlbumDownloadsAndDrawsTheBase() = runTest {
        val result = composer.compose(airbag, phone, Settings())

        assertFalse(result.reusedBase)
        assertEquals(1, art.downloads)
        assertEquals(1080, result.bitmap.width)
        assertEquals(2400, result.bitmap.height)
    }

    @Test
    fun anotherTrackOnTheSameAlbumDownloadsNothing() = runTest {
        composer.compose(airbag, phone, Settings())
        val result = composer.compose(lucky, phone, Settings())

        assertTrue(result.reusedBase)
        assertEquals(1, art.downloads)
    }

    @Test
    fun theCacheSurvivesANewComposer() = runTest {
        composer.compose(airbag, phone, Settings())
        val fresh = WallpaperComposer(art, WallpaperRenderer(), AlbumBaseCache(dir))

        assertTrue(fresh.compose(lucky, phone, Settings()).reusedBase)
        assertEquals(1, art.downloads)
    }

    @Test
    fun aDifferentAlbumDownloadsAgain() = runTest {
        composer.compose(airbag, phone, Settings())
        composer.compose(otherAlbum, phone, Settings())
        assertEquals(2, art.downloads)
    }

    @Test
    fun changingALookSettingRedrawsTheBase() = runTest {
        composer.compose(airbag, phone, Settings())
        val result = composer.compose(lucky, phone, Settings(cornerRadius = 4))

        assertFalse(result.reusedBase)
        assertEquals(2, art.downloads)
    }

    @Test
    fun anAlbumWithNoArtIsReportedAsUndrawable() = runTest {
        // Spotify does return albums with no images. The engine relies on this
        // exact type to skip the track instead of retrying it on every poll.
        val artless = airbag.copy(artUrl = null)

        val thrown = runCatching { composer.compose(artless, phone, Settings()) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is TrackNotDrawableException)
        assertEquals(0, art.downloads)
    }

    @Test
    fun aTrackWithNoAlbumIsReportedAsUndrawable() = runTest {
        val albumless = airbag.copy(albumId = null)

        val thrown = runCatching { composer.compose(albumless, phone, Settings()) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is TrackNotDrawableException)
    }

    @Test
    fun trackTextDiffersWhileTheBaseIsShared() = runTest {
        val first = composer.compose(airbag, phone, Settings()).bitmap
        val second = composer.compose(lucky, phone, Settings()).bitmap
        assertFalse(first.sameAs(second))
    }

    // --- obtainBase (the share image starts from the same base) ------------

    @Test
    fun obtainingABaseThatIsNotCachedDrawsAndCachesIt() = runTest {
        val obtained = composer.obtainBase(airbag, phone, Settings())
        try {
            assertFalse(obtained.fromCache)
            assertEquals(1080, obtained.base.base.bitmap.width)
            assertEquals(640, obtained.base.sourceArtSidePx)
        } finally {
            obtained.base.base.bitmap.recycle()
        }

        // The wallpaper then reuses it: drawing it for a share is not wasted.
        assertTrue(composer.compose(lucky, phone, Settings()).reusedBase)
        assertEquals(1, art.downloads)
    }

    @Test
    fun obtainingTheBaseOfTheWallpaperDownloadsNothing() = runTest {
        composer.compose(airbag, phone, Settings())

        val obtained = composer.obtainBase(lucky, phone, Settings())
        try {
            assertTrue(obtained.fromCache)
            assertEquals(640, obtained.base.sourceArtSidePx)
        } finally {
            obtained.base.base.bitmap.recycle()
        }
        assertEquals(1, art.downloads)
    }

    @Test
    fun obtainingABaseForATrackWithNoAlbumIsReportedAsUndrawable() = runTest {
        val thrown = runCatching { composer.obtainBase(airbag.copy(albumId = null), phone, Settings()) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is TrackNotDrawableException)
    }

    private class CountingArtSource : ArtSource {
        var downloads = 0
            private set

        private val png: ByteArray = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(Color.rgb(40, 90, 160)) }
                .compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()

        override suspend fun download(url: String): ByteArray {
            downloads++
            return png
        }
    }
}
