package io.github.arthur044.wallpaperchanger.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The fields of a GitHub Release (REST API) that finding an update needs. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    /** The release title; for debug builds, the exact branch name. */
    val name: String? = null,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
) {
    /** Where to download this release's update.json; null if it has none. */
    val updateJsonUrl: String? get() = assetUrl(UPDATE_JSON)

    fun assetUrl(fileName: String): String? = assets.firstOrNull { it.name == fileName }?.downloadUrl
}

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)

/** A branch with a debug build to install, and the release that holds it. */
data class DebugBranch(val branch: String, val release: GithubRelease)

/** The response wasn't the JSON GitHub documents, or a release lacks a file it should have. */
class InvalidReleaseResponseException(message: String, cause: Throwable? = null) : Exception(message, cause)

const val UPDATE_JSON = "update.json"

/** Tags of debug pre-releases start with this (see android/ci/debug-tag.sh). */
const val DEBUG_TAG_PREFIX = "debug-"

private val json = Json { ignoreUnknownKeys = true }

/** `GET /repos/{owner}/{repo}/releases/latest`: the newest non-pre-release. */
fun parseRelease(text: String): GithubRelease = decode(text)

/** `GET /repos/{owner}/{repo}/releases`: newest first. */
fun parseReleases(text: String): List<GithubRelease> = decode(text)

private inline fun <reified T> decode(text: String): T = try {
    json.decodeFromString<T>(text)
} catch (e: SerializationException) {
    throw InvalidReleaseResponseException("Unexpected GitHub response: ${e.message}", e)
} catch (e: IllegalArgumentException) {
    throw InvalidReleaseResponseException("Unexpected GitHub response: ${e.message}", e)
}

/**
 * The branches that have a debug build, once each, in the order GitHub lists
 * them (newest first). The branch name is the release title; a release
 * without one falls back to its tag's slug, which may have lost a "/".
 * Releases without an update.json are skipped: there is nothing to install.
 */
fun debugBranches(releases: List<GithubRelease>): List<DebugBranch> =
    releases
        .filter { it.prerelease && it.tagName.startsWith(DEBUG_TAG_PREFIX) && it.updateJsonUrl != null }
        .map { DebugBranch(branch = it.name?.takeIf(String::isNotBlank) ?: it.tagName.removePrefix(DEBUG_TAG_PREFIX), release = it) }
        .distinctBy { it.branch }

/**
 * The branch to select first: the installed build's own, while it still has
 * a build; otherwise the newest one. Null when there are none.
 */
fun preselectedBranch(branches: List<DebugBranch>, installedBranch: String?): DebugBranch? =
    branches.firstOrNull { it.branch == installedBranch } ?: branches.firstOrNull()
