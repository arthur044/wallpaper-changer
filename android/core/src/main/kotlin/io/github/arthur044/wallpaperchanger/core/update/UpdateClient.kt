package io.github.arthur044.wallpaperchanger.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** GitHub's API allows 60 calls an hour without a token; this one went over. */
class UpdateRateLimitedException(val resetsAt: Instant?) : Exception("GitHub API rate limit reached, resets at $resetsAt")

/** No connection, a timeout, or a server error: trying again later may work. */
class UpdateNetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The downloaded APK isn't the one update.json describes: never install it. */
class ChecksumMismatchException(message: String) : Exception(message)

/** What checking a channel found, against the installed build. */
sealed interface UpdateCheck {
    /** Nothing has been published for this channel or branch yet. */
    data object NoBuild : UpdateCheck

    data class Found(val info: UpdateInfo, val release: GithubRelease, val comparison: VersionComparison) : UpdateCheck

    /** The build is for another app id (a release offered to the debug app, say). */
    data class WrongPackage(val info: UpdateInfo) : UpdateCheck
}

/** Where builds come from; [UpdateClient] in the app, a fake in tests. */
interface UpdateSource {
    suspend fun checkRelease(installedPackage: String, installedVersionCode: Int): UpdateCheck

    suspend fun debugBranches(): List<DebugBranch>

    suspend fun checkDebug(branch: DebugBranch, installedPackage: String, installedVersionCode: Int): UpdateCheck

    suspend fun download(found: UpdateCheck.Found, dir: File): File
}

/**
 * Finds and downloads builds published by CI as GitHub Releases: the release
 * channel is the latest release, the debug channel one pre-release per branch.
 * Unauthenticated: public repo, and a token in the app would be a secret on
 * every phone. That caps checks at 60 an hour, fine for a manual button.
 */
class UpdateClient(
    private val apiBase: HttpUrl = DEFAULT_API_BASE,
    private val http: OkHttpClient = defaultHttpClient(),
) : UpdateSource {
    /** The release channel: the newest release of main (GitHub leaves pre-releases out). */
    override suspend fun checkRelease(installedPackage: String, installedVersionCode: Int): UpdateCheck {
        val release = getOrNull(apiBase.newBuilder().addPathSegments("releases/latest").build())
            ?.let(::parseRelease)
            ?: return UpdateCheck.NoBuild
        return check(release, installedPackage, installedVersionCode)
    }

    /**
     * The branches with a debug build, newest first. Every push to main adds
     * a release, so the list is paged: one call while there are under 100,
     * up to [MAX_RELEASE_PAGES] (each call counts against the hourly limit).
     */
    override suspend fun debugBranches(): List<DebugBranch> {
        val releases = mutableListOf<GithubRelease>()
        for (page in 1..MAX_RELEASE_PAGES) {
            val url = apiBase.newBuilder().addPathSegment("releases")
                .addQueryParameter("per_page", RELEASES_PER_PAGE.toString())
                .addQueryParameter("page", page.toString())
                .build()
            val batch = parseReleases(getOrNull(url) ?: break)
            releases += batch
            if (batch.size < RELEASES_PER_PAGE) break
        }
        return debugBranches(releases)
    }

    /** The debug channel: the build of [branch]. */
    override suspend fun checkDebug(branch: DebugBranch, installedPackage: String, installedVersionCode: Int): UpdateCheck =
        check(branch.release, installedPackage, installedVersionCode)

    private suspend fun check(release: GithubRelease, installedPackage: String, installedVersionCode: Int): UpdateCheck {
        val jsonUrl = release.updateJsonUrl ?: return UpdateCheck.NoBuild
        val info = parseUpdateInfo(getOrNull(jsonUrl.toHttpUrl()) ?: return UpdateCheck.NoBuild)
        if (info.`package` != installedPackage) return UpdateCheck.WrongPackage(info)
        return UpdateCheck.Found(info, release, compareVersion(installedVersionCode, info))
    }

    /**
     * Downloads the build's APK into [dir] (emptied first: one download is
     * kept) and checks it against update.json. A file that doesn't match is
     * deleted, never returned.
     */
    override suspend fun download(found: UpdateCheck.Found, dir: File): File {
        val url = found.release.assetUrl(found.info.apk)
            ?: throw UpdateNetworkException("The release has no ${found.info.apk}")
        val target = File(dir, found.info.apk)
        val partial = File(dir, "${found.info.apk}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        send(url.toHttpUrl()).use { response ->
            if (!response.isSuccessful) throw UpdateNetworkException("Download failed: HTTP ${response.code}")
            try {
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    dir.listFiles()?.forEach { it.delete() }
                    response.body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                partial.delete()
                throw UpdateNetworkException("Download cut off: ${e.message}", e)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != found.info.sha256) {
            partial.delete()
            throw ChecksumMismatchException("${found.info.apk} has SHA-256 $actual, update.json says ${found.info.sha256}")
        }
        if (!partial.renameTo(target)) throw UpdateNetworkException("Could not save ${target.name}")
        return target
    }

    // Body text, or null for a 404 (nothing published there).
    private suspend fun getOrNull(url: HttpUrl): String? = send(url).use { r ->
        when {
            r.isSuccessful -> try {
                withContext(Dispatchers.IO) { r.body.string() }
            } catch (e: IOException) {
                throw UpdateNetworkException("Response cut off: ${e.message}", e)
            }
            r.code == HTTP_NOT_FOUND -> null
            isRateLimited(r) -> throw UpdateRateLimitedException(
                r.header("x-ratelimit-reset")?.toLongOrNull()?.let(Instant::ofEpochSecond),
            )
            else -> throw UpdateNetworkException("GitHub returned HTTP ${r.code} for ${url.encodedPath}")
        }
    }

    private suspend fun send(url: HttpUrl): Response = try {
        http.newCall(
            Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build(),
        ).executeAsync()
    } catch (e: IOException) {
        throw UpdateNetworkException("GitHub unreachable: ${e.message}", e)
    }

    // GitHub answers an exhausted limit with 403 (primary) or 429 (secondary).
    private fun isRateLimited(r: Response): Boolean =
        r.code == HTTP_TOO_MANY_REQUESTS || (r.code == HTTP_FORBIDDEN && r.header("x-ratelimit-remaining") == "0")

    companion object {
        val DEFAULT_API_BASE: HttpUrl = "https://api.github.com/repos/arthur044/wallpaper-changer/".toHttpUrl()

        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val BUFFER_BYTES = 64 * 1024
        private const val RELEASES_PER_PAGE = 100
        private const val MAX_RELEASE_PAGES = 5

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10.seconds.toJavaDuration())
            .readTimeout(30.seconds.toJavaDuration())
            // An APK is ~15 MB: give a slow connection time, but not forever.
            .callTimeout(5.minutes.toJavaDuration())
            .build()
    }
}
