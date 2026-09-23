package io.github.arthur044.wallpaperchanger.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.arthur044.wallpaperchanger.R
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.config.ArtFrame
import io.github.arthur044.wallpaperchanger.core.config.BackgroundStyle
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.config.TextCard
import io.github.arthur044.wallpaperchanger.core.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainContentTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val airbag = NowPlaying(true, "t1", "a1", "https://i.scdn.co/image/a1", "Airbag", "Radiohead")

    private fun text(id: Int) = context.getString(id)

    private fun state(
        syncEnabled: Boolean = true,
        status: SyncStatus = SyncStatus.Showing(airbag),
        signedIn: Boolean = true,
        settings: Settings = Settings(),
        liveWallpaperActive: Boolean = false,
    ) = MainUiState(syncEnabled, status, signedIn, settings, liveWallpaperActive = liveWallpaperActive)

    private fun show(state: MainUiState, callbacks: MainCallbacks = MainCallbacks()) {
        rule.setContent { AppTheme { MainContent(state, callbacks) } }
    }

    @Test
    fun showsWhatIsOnTheWallpaper() {
        show(state())
        rule.onNodeWithTag(TAG_STATUS).assertTextEquals("Airbag — Radiohead")
    }

    @Test
    fun syncOffSaysSoWhateverTheLastStatus() {
        show(state(syncEnabled = false))
        rule.onNodeWithTag(TAG_STATUS).assertTextEquals(context.getString(R.string.sync_status_off))
    }

    @Test
    fun theSwitchTurnsSyncOn() {
        var requested: Boolean? = null
        show(state(syncEnabled = false), MainCallbacks(onSyncEnabledChange = { requested = it }))

        rule.onNodeWithTag(TAG_SYNC_SWITCH).performClick()

        assertEquals(true, requested)
    }

    @Test
    fun hidingTheTrackInfoIsALookChange() {
        var changed: Settings? = null
        show(state(), MainCallbacks(onLookChange = { changed = it(Settings()) }))

        rule.onNodeWithTag(TAG_TRACK_INFO).performScrollTo().performClick()

        assertEquals(false, changed?.showTrackInfo)
    }

    @Test
    fun theStyleSwitchesAreLookChanges() {
        var changed: Settings? = null
        show(state(), MainCallbacks(onLookChange = { changed = it(changed ?: Settings()) }))

        rule.onNodeWithTag("${TAG_BACKGROUND}_1").performScrollTo().performClick()
        rule.onNodeWithTag(TAG_GLOW).performScrollTo().performClick()
        rule.onNodeWithTag(TAG_GLASS).performScrollTo().performClick()

        assertEquals(BackgroundStyle.MESH, changed?.backgroundStyle)
        assertEquals(true, changed?.artGlow)
        assertEquals(TextCard.GLASS, changed?.textCard)
    }

    @Test
    fun theSmoothTransitionSwitchReportsItsNewValue() {
        var requested: Boolean? = null
        show(state(), MainCallbacks(onSmoothTransitionChange = { requested = it }))

        rule.onNodeWithTag(TAG_SMOOTH).performScrollTo().performClick()

        assertEquals(true, requested)
    }

    @Test
    fun onButNotPickedOffersThePicker() {
        var picked = false
        show(
            state(settings = Settings(smoothTransition = true), liveWallpaperActive = false),
            MainCallbacks(onPickLiveWallpaper = { picked = true }),
        )

        rule.onNodeWithTag(TAG_PICK_LIVE).performScrollTo().performClick()

        assertTrue(picked)
    }

    @Test
    fun oncePickedThereIsNothingToAsk() {
        show(state(settings = Settings(smoothTransition = true), liveWallpaperActive = true))

        rule.onNodeWithTag(TAG_SMOOTH).performScrollTo().assertExists()
        rule.onNodeWithTag(TAG_PICK_LIVE).assertDoesNotExist()
    }

    @Test
    fun theBlurredBackgroundAndTheFrameArePicked() {
        var changed: Settings? = null
        show(state(), MainCallbacks(onLookChange = { changed = it(changed ?: Settings()) }))

        rule.onNodeWithTag("${TAG_BACKGROUND}_2").performScrollTo().performClick()
        rule.onNodeWithTag("${TAG_FRAME}_1").performScrollTo().performClick()

        assertEquals(BackgroundStyle.BLUR, changed?.backgroundStyle)
        assertEquals(ArtFrame.SINGLE, changed?.artFrame)
    }

    @Test
    fun theBlurStrengthSliderOnlyShowsWithTheBlurredBackground() {
        var changed: Settings? = null
        show(state(settings = Settings(backgroundStyle = BackgroundStyle.BLUR)), MainCallbacks(onLookChange = { changed = it(Settings()) }))

        rule.onNodeWithTag(TAG_BLUR_STRENGTH).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }

        assertEquals(60, changed?.blurStrength)
    }

    @Test
    fun noBlurSliderWithOtherBackgrounds() {
        show(state())

        rule.onNodeWithTag("${TAG_BACKGROUND}_0").performScrollTo().assertExists()
        rule.onNodeWithTag(TAG_BLUR_STRENGTH).assertDoesNotExist()
    }

    @Test
    fun pickingTheCurrentChoiceChangesNothing() {
        var changed: Settings? = null
        show(state(), MainCallbacks(onLookChange = { changed = it(Settings()) }))

        rule.onNodeWithTag("${TAG_FRAME}_0").performScrollTo().performClick()

        assertNull(changed)
    }

    @Test
    fun theGlassCardNeedsTheTrackInfo() {
        show(state(settings = Settings(showTrackInfo = false)), MainCallbacks())

        rule.onNodeWithTag(TAG_GLASS).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aSliderCommitsItsValueAsALookChange() {
        var changed: Settings? = null
        show(state(), MainCallbacks(onLookChange = { changed = it(Settings()) }))

        rule.onNodeWithTag(TAG_ART_SIZE).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }

        assertEquals(0.5, changed?.artSizePct)
    }

    @Test
    fun thePollIntervalIsSavedWithoutARedraw() {
        var look: Settings? = null
        var plain: Settings? = null
        show(state(), MainCallbacks(onLookChange = { look = it(Settings()) }, onSettingsChange = { plain = it(Settings()) }))

        rule.onNodeWithTag(TAG_POLL).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }

        assertEquals(60.0, plain?.webApiPollIntervalSeconds)
        assertNull(look)
    }

    @Test
    fun turningOnInstantWithoutAccessAsksForItInstead() {
        var granted = false
        var changed: Boolean? = null
        show(
            state().copy(notificationAccess = false),
            MainCallbacks(onGrantNotificationAccess = { granted = true }, onInstantChange = { changed = it }),
        )

        rule.onNodeWithTag(TAG_MEDIA_SESSION).performScrollTo().performClick()

        assertEquals(true, granted)
        assertNull(changed) // the option isn't claimed to be on without the permission
    }

    @Test
    fun withAccessTheInstantSwitchJustSetsTheOption() {
        var changed: Boolean? = null
        show(state().copy(notificationAccess = true), MainCallbacks(onInstantChange = { changed = it }))

        rule.onNodeWithTag(TAG_MEDIA_SESSION).performScrollTo().performClick()

        assertEquals(true, changed)
    }

    @Test
    fun anOptionLeftOnWithoutAccessOffersTheWayBack() {
        var granted = false
        show(
            state(settings = Settings(useMediaSession = true)).copy(notificationAccess = false),
            MainCallbacks(onGrantNotificationAccess = { granted = true }),
        )

        rule.onNodeWithTag(TAG_GRANT_ACCESS).performScrollTo().performClick()

        assertEquals(true, granted)
    }

    @Test
    fun theNoNotificationModeIsOnlyOfferedOnceInstantWorks() {
        show(state(settings = Settings(useMediaSession = true)).copy(notificationAccess = false))
        rule.onNodeWithTag(TAG_MEDIA_SESSION).assertExists() // the section rendered
        rule.onNodeWithTag(TAG_LOCAL_ONLY).assertDoesNotExist()
    }

    @Test
    fun withInstantWorkingTheNoNotificationModeCanBeTurnedOn() {
        var localOnly: Boolean? = null
        show(
            state(settings = Settings(useMediaSession = true)).copy(notificationAccess = true),
            MainCallbacks(onLocalOnlyChange = { localOnly = it }),
        )

        rule.onNodeWithTag(TAG_LOCAL_ONLY).performScrollTo().performClick()

        assertEquals(true, localOnly)
    }

    @Test
    fun theDebugLinkIsHiddenOutsideDebugBuilds() {
        show(state()) // showDebugTools defaults to false, as in a release build

        rule.onNodeWithTag(TAG_SYNC_SWITCH).assertExists() // the screen did render
        rule.onNodeWithText(text(R.string.main_debug_tools)).assertDoesNotExist()
    }

    @Test
    fun aDebugBuildKeepsTheLink() {
        var opened = false
        show(state().copy(showDebugTools = true), MainCallbacks(onOpenDebug = { opened = true }))

        rule.onNodeWithText(text(R.string.main_debug_tools)).performScrollTo().performClick()

        assertEquals(true, opened)
    }

    @Test
    fun signedOutOffersToConnect() {
        var connect = false
        show(state(signedIn = false), MainCallbacks(onConnect = { connect = true }))

        rule.onNodeWithText(context.getString(R.string.main_signed_out_action)).performClick()

        assertEquals(true, connect)
    }
}
