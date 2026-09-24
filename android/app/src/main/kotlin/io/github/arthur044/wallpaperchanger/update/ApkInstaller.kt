package io.github.arthur044.wallpaperchanger.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import io.github.arthur044.wallpaperchanger.core.update.Installer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Installs a downloaded APK over this app through a PackageInstaller session
 * (ACTION_INSTALL_PACKAGE is deprecated since Android 10). The outcome
 * arrives later at [InstallResultReceiver]: the system first asks the user to
 * confirm, and on success replaces this very process.
 */
class ApkInstaller(private val context: Context) : Installer {
    // The session whose result the update screen waits for. Abandoning an
    // older, committed one makes the system report it as aborted too; that
    // report must not end the wait for the new one.
    private val currentSession = AtomicInteger(NO_SESSION)

    /** Whether a result is about the session being installed now. */
    fun isCurrent(sessionId: Int): Boolean = sessionId != NO_SESSION && sessionId == currentSession.get()

    /** Whether the user let this app install apps ("install unknown apps"). */
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Streams [apk] into a new session and commits it. Blocking I/O runs off the main thread. */
    override suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        // First: from here on no older session is "current", whenever its
        // abort report arrives.
        currentSession.set(NO_SESSION)
        val installer = context.packageManager.packageInstaller
        // A retry after an unanswered confirmation: drop the old session (and
        // its copy of the APK) instead of leaving it for the system's cleanup.
        installer.mySessions.forEach { installer.abandonSession(it.sessionId) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        currentSession.set(sessionId)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(resultIntent(sessionId).intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }

    // Mutable: the installer adds the status extras. Explicit, so only our receiver gets it.
    private fun resultIntent(sessionId: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, InstallResultReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private companion object {
        const val NO_SESSION = -1
    }
}
