package org.thoughtcrime.securesms.webrtc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.SparseArray
import org.session.libsignal.utilities.Log

class NetworkChangeReceiver(private val onNetworkChangedCallback: (Boolean)->Unit) {

    private val networkList: MutableSet<Network> = mutableSetOf()

    val broadcastDelegate = object: BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            receiveBroadcast(context, intent)
        }
    }

    private fun checkNetworks() {

    }

    val defaultObserver = object: ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d("Loki", "onAvailable: $network")
            networkList += network
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            Log.d("Loki", "onLosing: $network, maxMsToLive: $maxMsToLive")
        }

        override fun onLost(network: Network) {
            Log.d("Loki", "onLost: $network")
            networkList -= network
            onNetworkChangedCallback(networkList.isNotEmpty())
        }

        override fun onUnavailable() {
            Log.d("Loki", "onUnavailable")
        }
    }

    fun receiveBroadcast(context: Context, intent: Intent) {
        Log.d("Loki", intent.toString())
        onNetworkChangedCallback(context.isConnected())
    }

    fun Context.isConnected() : Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.isConnected ?: false
    }

    fun register(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(defaultObserver)
        } else {
            val intentFilter = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
            context.registerReceiver(broadcastDelegate, intentFilter)
        }
    }

    fun unregister(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(defaultObserver)
        } else {
            context.unregisterReceiver(broadcastDelegate)
        }
    }

}