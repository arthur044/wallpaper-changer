package io.github.arthur044.wallpaperchanger.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLogicTest {
    private val fresh = OnboardingFacts(
        hasClientId = false,
        signedIn = false,
        notificationsAllowed = false,
        batteryUnrestricted = false,
    )
    private val ready = OnboardingFacts(
        hasClientId = true,
        signedIn = true,
        notificationsAllowed = true,
        batteryUnrestricted = true,
    )

    @Test
    fun `a fresh install starts at the Client ID`() {
        assertEquals(OnboardingStep.CLIENT_ID, currentStep(fresh))
    }

    @Test
    fun `steps come in order, each once the previous is satisfied`() {
        assertEquals(OnboardingStep.LOGIN, currentStep(fresh.copy(hasClientId = true)))
        assertEquals(OnboardingStep.NOTIFICATIONS, currentStep(fresh.copy(hasClientId = true, signedIn = true)))
        assertEquals(
            OnboardingStep.BATTERY,
            currentStep(fresh.copy(hasClientId = true, signedIn = true, notificationsAllowed = true)),
        )
        assertEquals(OnboardingStep.DONE, currentStep(ready))
    }

    @Test
    fun `already granted steps never show`() {
        assertEquals(OnboardingStep.DONE, currentStep(ready))
        assertEquals(OnboardingStep.BATTERY, currentStep(ready.copy(batteryUnrestricted = false)))
    }

    @Test
    fun `optional steps can be skipped, required ones cannot`() {
        val skipAll = OnboardingStep.entries.toSet()
        assertEquals(OnboardingStep.DONE, currentStep(ready.copy(notificationsAllowed = false, batteryUnrestricted = false, skipped = skipAll)))
        assertEquals(OnboardingStep.CLIENT_ID, currentStep(fresh.copy(skipped = skipAll)))
        assertEquals(OnboardingStep.LOGIN, currentStep(fresh.copy(hasClientId = true, skipped = skipAll)))
    }

    @Test
    fun `losing the session goes back to login`() {
        assertEquals(OnboardingStep.LOGIN, currentStep(ready.copy(signedIn = false)))
    }

    @Test
    fun `client ids are 32 hex digits`() {
        assertTrue(isValidClientId("f661e57c0123456789abcdef0123cf0f"))
        assertTrue(isValidClientId("  F661E57C0123456789ABCDEF0123CF0F  "))
        assertFalse(isValidClientId(""))
        assertFalse(isValidClientId("f661e57c0123456789abcdef0123cf0")) // 31
        assertFalse(isValidClientId("f661e57c0123456789abcdef0123cf0fa")) // 33
        assertFalse(isValidClientId("g661e57c0123456789abcdef0123cf0f")) // not hex
        assertFalse(isValidClientId("f661e57c-0123-4567-89ab-cdef0123cf0f"))
    }
}
