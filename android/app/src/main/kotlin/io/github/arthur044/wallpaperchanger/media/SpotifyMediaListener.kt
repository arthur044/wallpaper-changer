package io.github.arthur044.wallpaperchanger.media

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Enabled by the user under "Notification access". The service itself does
 * nothing: its only job is to exist, because MediaSessionManager only hands
 * out other apps' sessions to a component the user allowed this way.
 */
class SpotifyMediaListener : NotificationListenerService()

/** Whether the user granted notification access, i.e. whether sessions are readable. */
fun Context.notificationAccessGranted(): Boolean =
    packageName in NotificationManagerCompat.getEnabledListenerPackages(this)

fun Context.mediaListenerComponent(): ComponentName = ComponentName(this, SpotifyMediaListener::class.java)
