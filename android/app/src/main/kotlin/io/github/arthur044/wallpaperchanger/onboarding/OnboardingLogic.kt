package io.github.arthur044.wallpaperchanger.onboarding

/** The first-run steps, in order. Only DONE has no condition. */
enum class OnboardingStep { CLIENT_ID, LOGIN, NOTIFICATIONS, BATTERY, DONE }

/** What the phone and the app look like right now, as far as onboarding cares. */
data class OnboardingFacts(
    val hasClientId: Boolean,
    val signedIn: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    /** Optional steps the user chose to skip in this run. */
    val skipped: Set<OnboardingStep> = emptySet(),
)

/**
 * The step to show: the first one not yet satisfied. Derived from the facts
 * every time, so revoking something (say, signing out) leads back to its step
 * and a step that is already fine (permission granted earlier) never shows.
 */
fun currentStep(facts: OnboardingFacts): OnboardingStep = when {
    !facts.hasClientId -> OnboardingStep.CLIENT_ID
    !facts.signedIn -> OnboardingStep.LOGIN
    !facts.notificationsAllowed && OnboardingStep.NOTIFICATIONS !in facts.skipped -> OnboardingStep.NOTIFICATIONS
    !facts.batteryUnrestricted && OnboardingStep.BATTERY !in facts.skipped -> OnboardingStep.BATTERY
    else -> OnboardingStep.DONE
}

/** Steps shown as "n of N": everything but DONE. */
val ONBOARDING_STEP_COUNT = OnboardingStep.entries.size - 1

/** Spotify Client IDs are 32 hex digits; checked before any login attempt. */
fun isValidClientId(text: String): Boolean = CLIENT_ID_PATTERN.matches(text.trim())

private val CLIENT_ID_PATTERN = Regex("^[0-9a-fA-F]{32}$")
