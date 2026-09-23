package io.github.arthur044.wallpaperchanger.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Stored under the desktop's config.json values, so either config reads the
// other. An unknown value falls back to the field's default on read
// (SettingsSerializer's coerceInputValues) instead of failing the whole file.

/** What fills the canvas around the art. */
@Serializable
enum class BackgroundStyle {
    /** The art's dominant color: the original look. */
    @SerialName("solid") SOLID,

    /** A soft gradient of 2-4 of the art's colors. */
    @SerialName("mesh") MESH,

    /** The art itself covering the screen, blurred and darkened toward the edges. */
    @SerialName("blur") BLUR,
}

/** Glass rims around the art, which shrinks to fit them. */
@Serializable
enum class ArtFrame {
    @SerialName("none") NONE,

    @SerialName("single") SINGLE,

    @SerialName("double") DOUBLE,
}

/** What sits behind the track text. */
@Serializable
enum class TextCard {
    @SerialName("none") NONE,

    /** A frosted-glass card over the background. */
    @SerialName("glass") GLASS,
}
