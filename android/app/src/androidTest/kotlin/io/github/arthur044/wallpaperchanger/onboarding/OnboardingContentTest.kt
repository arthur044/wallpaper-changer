package io.github.arthur044.wallpaperchanger.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.arthur044.wallpaperchanger.R
import io.github.arthur044.wallpaperchanger.ui.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingContentTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val redirect = "io.github.arthur044.wallpaperchanger://callback"
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(ui: OnboardingUi, callbacks: OnboardingCallbacks = OnboardingCallbacks()) {
        rule.setContent { AppTheme { OnboardingContent(ui, callbacks) } }
    }

    @Test
    fun theClientIdMustBeValidBeforeContinuing() {
        var saved: String? = null
        show(OnboardingUi(OnboardingStep.CLIENT_ID, redirect), OnboardingCallbacks(onSaveClientId = { saved = it }))
        val field = rule.onNodeWithTag(TAG_CLIENT_ID)
        val proceed = rule.onNodeWithText(text(R.string.onb_continue))

        field.performScrollTo().performTextInput("not-a-client-id")
        proceed.performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.onb_client_id_invalid)).assertExists()

        field.performTextReplacement(" f661e57c0123456789abcdef0123cf0f ")
        proceed.assertIsEnabled().performClick()

        assertEquals("f661e57c0123456789abcdef0123cf0f", saved)
    }

    @Test
    fun theRedirectUriIsShownToCopy() {
        var copied = false
        show(OnboardingUi(OnboardingStep.CLIENT_ID, redirect), OnboardingCallbacks(onCopyRedirectUri = { copied = true }))

        rule.onNodeWithText(redirect).assertExists()
        rule.onNodeWithText(text(R.string.onb_copy)).performScrollTo().performClick()

        assertEquals(true, copied)
    }

    @Test
    fun aCancelledLoginPointsAtTheRedirectUri() {
        show(OnboardingUi(OnboardingStep.LOGIN, redirect, loginProblem = LoginProblem.Cancelled))
        rule.onNodeWithTag(TAG_LOGIN_PROBLEM).assertTextContains(redirect, substring = true)
    }

    @Test
    fun aLoginInProgressCannotBeStartedTwice() {
        show(OnboardingUi(OnboardingStep.LOGIN, redirect, loginInProgress = true))
        rule.onNodeWithText(text(R.string.onb_login_button)).assertIsNotEnabled()
    }

    @Test
    fun progressCountsTheStep() {
        show(OnboardingUi(OnboardingStep.LOGIN, redirect))
        rule.onNodeWithTag(TAG_PROGRESS).assertTextEquals(text(R.string.onb_step_progress, 2, ONBOARDING_STEP_COUNT))
    }

    @Test
    fun notificationsCanBeSkipped() {
        var skipped: OnboardingStep? = null
        show(OnboardingUi(OnboardingStep.NOTIFICATIONS, redirect), OnboardingCallbacks(onSkip = { skipped = it }))

        rule.onNodeWithText(text(R.string.onb_not_now)).performScrollTo().performClick()

        assertEquals(OnboardingStep.NOTIFICATIONS, skipped)
    }

    @Test
    fun samsungGetsTheSleepingAppsHint() {
        show(OnboardingUi(OnboardingStep.BATTERY, redirect, isSamsung = true))
        rule.onNodeWithTag(TAG_SAMSUNG_HINT).assertExists()
    }

    @Test
    fun otherBrandsDontSeeTheSamsungHint() {
        show(OnboardingUi(OnboardingStep.BATTERY, redirect, isSamsung = false))
        // Proves the step rendered first: "does not exist" alone also passes on a blank screen.
        rule.onNodeWithText(text(R.string.onb_battery_title)).assertExists()
        rule.onNodeWithTag(TAG_SAMSUNG_HINT).assertDoesNotExist()
    }

    @Test
    fun startFinishesTheGuide() {
        var started = false
        show(OnboardingUi(OnboardingStep.DONE, redirect), OnboardingCallbacks(onStart = { started = true }))

        rule.onNodeWithText(text(R.string.onb_start)).performClick()

        assertEquals(true, started)
    }
}
