package io.github.arthur044.wallpaperchanger.core.update

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A published build, as described by the update.json that CI attaches to
 * every GitHub Release (android/ci/write-update-json.sh writes it).
 */
@Serializable
data class UpdateInfo(
    /** "release" or "debug". */
    val channel: String,
    /** The application id, ".debug" included for debug builds. */
    val `package`: String,
    val versionCode: Int,
    val versionName: String,
    val commit: String,
    /** The exact branch it was built from (a debug tag can't hold a "/"). */
    val branch: String,
    /** The APK's file name among the release's assets. */
    val apk: String,
    /** Hex SHA-256 of the APK: checked before installing it. */
    val sha256: String,
    val notes: String = "",
    val builtAt: String = "",
)

/** update.json was missing a field, had a wrong one, or wasn't JSON at all. */
class InvalidUpdateInfoException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val json = Json { ignoreUnknownKeys = true }

private val SHA256_HEX = Regex("[0-9a-f]{64}")

/** @throws InvalidUpdateInfoException rather than offering a build that can't be checked. */
fun parseUpdateInfo(text: String): UpdateInfo {
    val info = try {
        json.decodeFromString<UpdateInfo>(text)
    } catch (e: SerializationException) {
        throw InvalidUpdateInfoException("update.json is incomplete or malformed: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw InvalidUpdateInfoException("update.json is incomplete or malformed: ${e.message}", e)
    }
    val problem = when {
        info.versionCode <= 0 -> "versionCode must be positive, was ${info.versionCode}"
        info.`package`.isBlank() -> "package is empty"
        info.apk.isBlank() -> "apk is empty"
        !SHA256_HEX.matches(info.sha256) -> "sha256 is not 64 lowercase hex digits"
        else -> null
    }
    if (problem != null) throw InvalidUpdateInfoException("update.json: $problem")
    return info
}

enum class VersionComparison {
    /** The published build is newer: offer it. */
    NEWER,

    /** Already installed. */
    SAME,

    /**
     * Older than what is installed (a debug build of another branch, say).
     * Android refuses to install it over the newer one.
     */
    OLDER,
}

/** Where [available] stands against the installed build's versionCode. */
fun compareVersion(installedVersionCode: Int, available: UpdateInfo): VersionComparison = when {
    available.versionCode > installedVersionCode -> VersionComparison.NEWER
    available.versionCode == installedVersionCode -> VersionComparison.SAME
    else -> VersionComparison.OLDER
}
