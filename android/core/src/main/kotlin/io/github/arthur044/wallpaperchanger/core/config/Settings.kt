package io.github.arthur044.wallpaperchanger.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * User configuration, the Android counterpart of the desktop `config.json`.
 * Keys keep the desktop names so a desktop config can be read as-is; desktop
 * keys with no Android meaning (redirect_uri, scope, fallback_resolution,
 * log_level, ...) are simply ignored on read.
 */
@Serializable
data class Settings(
    @SerialName("client_id") val clientId: String = "",
    // How often the Web API is polled for what's playing. This is the call that
    // can trip Spotify's rate limit (429), hence the floor in POLL_INTERVAL_SECONDS_RANGE.
    @SerialName("fallback_poll_interval_seconds") val webApiPollIntervalSeconds: Double = 25.0,
    @SerialName("art_size_pct") val artSizePct: Double = 0.68,
    @SerialName("corner_radius") val cornerRadius: Int = 16,
    @SerialName("shadow_blur_radius") val shadowBlurRadius: Int = 24,
    @SerialName("show_track_info") val showTrackInfo: Boolean = true,
    @SerialName("sync_lock_screen") val syncLockScreen: Boolean = false,
    // Vertical nudge of the art, as a fraction of screen height (negative = up),
    // so it can clear the lock screen clock.
    @SerialName("art_offset_y_pct") val artOffsetYPct: Double = 0.0,
    // Android's use_smtc: react to the local Spotify MediaSession instead of
    // polling. Off by default because it needs notification access (opt-in).
    @SerialName("use_media_session") val useMediaSession: Boolean = false,
    @SerialName("paused") val paused: Boolean = false,
    // Android only: the user turned syncing on, so it is brought back after a
    // reboot or an app update. Stays on when the session expires, so a new
    // login resumes it.
    @SerialName("sync_enabled") val syncEnabled: Boolean = false,
    // Android only: the first-run guide was finished (optional steps may have
    // been skipped), so it isn't shown again on every launch.
    @SerialName("onboarding_done") val onboardingDone: Boolean = false,
    // Android only: with useMediaSession on, run from the notification listener
    // alone - no foreground service, no ongoing notification, and no polling
    // when nothing plays on this phone.
    @SerialName("local_only") val localOnly: Boolean = false,
    @SerialName("background_style") val backgroundStyle: BackgroundStyle = BackgroundStyle.SOLID,
    // The art's shadow in its most vivid color instead of black.
    @SerialName("art_glow") val artGlow: Boolean = false,
    @SerialName("text_card") val textCard: TextCard = TextCard.NONE,
    @SerialName("art_frame") val artFrame: ArtFrame = ArtFrame.NONE,
    // Android only: show the wallpaper through the app's own live wallpaper,
    // which fades between images; a static wallpaper blinks black on every change.
    @SerialName("smooth_transition") val smoothTransition: Boolean = false,
) {
    val webApiPollInterval: Duration get() = webApiPollIntervalSeconds.seconds

    /** Clamps each out-of-range value on its own, keeping the rest intact. */
    fun sanitized(): Settings = copy(
        clientId = clientId.trim(),
        webApiPollIntervalSeconds = webApiPollIntervalSeconds.clampOr(POLL_INTERVAL_SECONDS_RANGE, DEFAULTS.webApiPollIntervalSeconds),
        artSizePct = artSizePct.clampOr(ART_SIZE_PCT_RANGE, DEFAULTS.artSizePct),
        cornerRadius = cornerRadius.coerceIn(CORNER_RADIUS_RANGE),
        shadowBlurRadius = shadowBlurRadius.coerceIn(BLUR_RADIUS_RANGE),
        artOffsetYPct = artOffsetYPct.clampOr(ART_OFFSET_Y_PCT_RANGE, DEFAULTS.artOffsetYPct),
    )

    companion object {
        val POLL_INTERVAL_SECONDS_RANGE = 10.0..300.0
        val ART_SIZE_PCT_RANGE = 0.2..0.95
        val CORNER_RADIUS_RANGE = 0..200
        val BLUR_RADIUS_RANGE = 0..200
        val ART_OFFSET_Y_PCT_RANGE = -0.4..0.4

        private val DEFAULTS = Settings()
    }
}

// coerceIn passes NaN straight through, so NaN falls back to the default instead.
private fun Double.clampOr(range: ClosedFloatingPointRange<Double>, default: Double): Double =
    if (isNaN()) default else coerceIn(range)
