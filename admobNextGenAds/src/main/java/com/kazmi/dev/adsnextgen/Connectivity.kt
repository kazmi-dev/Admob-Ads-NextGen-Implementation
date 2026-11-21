package com.kazmi.dev.adsnextgen

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Created by Kazmi Dev.
 *
 * Network state used to avoid sending ad requests while offline.
 */
internal object Connectivity {

    @Volatile
    var isConnected = true
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var isRegistered = false

    fun register(context: Context) {
        if (isRegistered) return
        isRegistered = true
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        isConnected = manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isConnected = true
                listeners.forEach { it() }
            }

            override fun onLost(network: Network) {
                isConnected = false
            }
        })
    }

    fun doOnAvailable(listener: () -> Unit) {
        listeners += listener
    }
}
