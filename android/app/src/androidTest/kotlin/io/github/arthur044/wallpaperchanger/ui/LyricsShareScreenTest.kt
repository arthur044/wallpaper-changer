package io.github.arthur044.wallpaperchanger.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.arthur044.wallpaperchanger.R
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.share.VerseSelection
import io.github.arthur044.wallpaperchanger.share.SharePhase
import io.github.arthur044.wallpaperchanger.share.ShareUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsShareScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val airbag = NowPlaying(true, "t1", "a1", null, "Airbag", "Radiohead")
    private val lines = listOf("Placeholder line one", "Placeholder line two", "", "Placeholder line three")

    private fun state(phase: SharePhase, ready: Boolean = true, selection: VerseSelection? = null) =
        ShareUiState(open = true, nowPlaying = airbag, phase = phase, lines = lines, ready = ready, selection = selection)

    private fun show(state: ShareUiState, callbacks: ShareCallbacks = ShareCallbacks()) {
        rule.setContent { AppTheme { LyricsShareScreen(state, callbacks) } }
    }

    // A 9:16 preview, as the real one; its size is what squeezed the rest out.
    private fun choosingWithPreview() = state(SharePhase.CHOOSING, selection = VerseSelection(0, 1)).copy(
        preview = Bitmap.createBitmap(108, 192, Bitmap.Config.ARGB_8888),
        previewOf = VerseSelection(0, 1),
    )

    private fun showIn(width: Int, height: Int, state: ShareUiState, callbacks: ShareCallbacks) {
        rule.setContent {
            AppTheme { Box(Modifier.size(width.dp, height.dp)) { LyricsShareScreen(state, callbacks) } }
        }
    }

    @Test
    fun inLandscapeTheLinesAndTheShareButtonStayReachable() {
        var tapped: Int? = null
        var shared = false
        // The A71 on its side: 2400 x 1080 px at 2.625, minus the bars.
        showIn(840, 360, choosingWithPreview(), ShareCallbacks(onTap = { tapped = it }, onShare = { shared = true }))

        rule.onNodeWithTag(TAG_SHARE_BUTTON).assertIsDisplayed().performClick()
        rule.onNodeWithText("Placeholder line three").assertIsDisplayed().performClick()

        assertTrue(shared)
        assertEquals(3, tapped)
    }

    @Test
    fun onAShortPortraitScreenTheShareButtonStaysReachable() {
        var shared = false
        showIn(360, 520, choosingWithPreview(), ShareCallbacks(onShare = { shared = true }))

        rule.onNodeWithTag(TAG_SHARE_BUTTON).assertIsDisplayed().performClick()

        assertTrue(shared)
    }

    @Test
    fun loadingShowsAProgressIndicator() {
        show(state(SharePhase.LOADING))
        rule.onNodeWithTag(TAG_SHARE_LOADING).assertExists()
    }

    @Test
    fun eachDeadEndSaysWhy() {
        val cases = mapOf(
            SharePhase.NO_LYRICS to R.string.share_no_lyrics,
            SharePhase.INSTRUMENTAL to R.string.share_instrumental,
            SharePhase.NO_NETWORK to R.string.share_no_network,
            SharePhase.IMAGE_UNAVAILABLE to R.string.share_image_unavailable,
        )
        val phase = mutableStateOf(SharePhase.NO_LYRICS)
        rule.setContent { AppTheme { LyricsShareScreen(state(phase.value), ShareCallbacks()) } }
        for ((shown, message) in cases) {
            phase.value = shown
            rule.onNodeWithTag(TAG_SHARE_MESSAGE).assertTextEquals(context.getString(message))
        }
    }

    @Test
    fun noNetworkOffersToTryAgain() {
        var retried = false
        show(state(SharePhase.NO_NETWORK), ShareCallbacks(onRetry = { retried = true }))

        rule.onNodeWithText(context.getString(R.string.share_retry)).performClick()

        assertTrue(retried)
    }

    @Test
    fun tappingALineReportsItsIndex() {
        var tapped: Int? = null
        show(state(SharePhase.CHOOSING), ShareCallbacks(onTap = { tapped = it }))

        rule.onNodeWithText("Placeholder line three").performClick()

        assertEquals(3, tapped)
    }

    @Test
    fun sharingNeedsASelection() {
        show(state(SharePhase.CHOOSING))
        rule.onNodeWithTag(TAG_SHARE_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun aSelectionCanBeShared() {
        var shared = false
        show(state(SharePhase.CHOOSING, selection = VerseSelection(0, 1)), ShareCallbacks(onShare = { shared = true }))

        rule.onNodeWithTag(TAG_SHARE_BUTTON).assertIsEnabled().performClick()

        assertTrue(shared)
    }

    @Test
    fun linesWaitForTheImageToBeReady() {
        var tapped = false
        show(state(SharePhase.CHOOSING, ready = false), ShareCallbacks(onTap = { tapped = true }))

        rule.onNodeWithText("Placeholder line one").performClick()

        assertFalse(tapped)
        rule.onNodeWithTag(TAG_SHARE_BUTTON).assertIsNotEnabled()
    }
}
