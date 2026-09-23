package io.github.arthur044.wallpaperchanger.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Whether the screen is on (interactive), starting with the current state. */
fun Context.screenOnFlow(): Flow<Boolean> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(intent.action == Intent.ACTION_SCREEN_ON)
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
    }
    // System broadcasts still arrive with NOT_EXPORTED; other apps can't fake them.
    ContextCompat.registerReceiver(this@screenOnFlow, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    // Read after registering, so a change in between is not lost.
    trySend(getSystemService(PowerManager::class.java).isInteractive)
    awaitClose { unregisterReceiver(receiver) }
}.distinctUntilChanged()
