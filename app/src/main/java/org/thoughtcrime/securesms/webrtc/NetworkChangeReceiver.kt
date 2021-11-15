package org.thoughtcrime.securesms.webrtc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager

class NetworkChangeReceiver(private val onNetworkChangedCallback: (Boolean)->Unit): BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        onNetworkChangedCallback(context.isConnected())
    }

    fun Context.isConnected() : Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.isConnected ?: false
    }

}