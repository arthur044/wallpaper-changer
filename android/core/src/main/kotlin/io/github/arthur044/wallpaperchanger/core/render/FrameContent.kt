package io.github.arthur044.wallpaperchanger.core.render

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.config.BackgroundStyle
import io.github.arthur044.wallpaperchanger.core.config.Settings

/**
 * What a drawn frame shows, for [ScreenFrames]: the track and the settings
 * that touch pixels (those of baseCacheKey, plus the text card). Settings
 * like polling or pause are left out, so changing them keeps the frame drawn
 * for the other screen of a foldable.
 */
fun frameContent(nowPlaying: NowPlaying, settings: Settings): List<Any?> = listOf(
    nowPlaying.trackId, nowPlaying.albumId, nowPlaying.artUrl, nowPlaying.trackName, nowPlaying.artistName,
    settings.artSizePct, settings.cornerRadius, settings.shadowBlurRadius,
    settings.artOffsetYPct, settings.showTrackInfo,
    settings.backgroundStyle, settings.artGlow, settings.artFrame, settings.textCard,
    if (settings.backgroundStyle == BackgroundStyle.BLUR) settings.blurStrength else null,
)
