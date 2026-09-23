package io.github.arthur044.wallpaperchanger.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the default network has working internet. Only "validated" counts:
 * a Wi-Fi that is up but not yet online would just fail the poll and deepen
 * the backoff.
 */
fun Context.internetAvailableFlow(): Flow<Boolean> = callbackFlow {
    val manager = getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }

        override fun onLost(network: Network) {
            trySend(false)
        }
    }
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
