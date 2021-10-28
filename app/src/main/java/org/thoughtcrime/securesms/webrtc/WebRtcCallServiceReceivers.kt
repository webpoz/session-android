package org.thoughtcrime.securesms.webrtc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsignal.utilities.Log
import javax.inject.Inject


class HangUpRtcOnPstnCallAnsweredListener(private val hangupListener: ()->Unit): PhoneStateListener() {

    companion object {
        private val TAG = Log.tag(HangUpRtcOnPstnCallAnsweredListener::class.java)
    }

    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
        super.onCallStateChanged(state, phoneNumber)
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            hangupListener()
            Log.i(TAG, "Device phone call ended Session call.")
        }
    }
}

@AndroidEntryPoint
class NetworkReceiver: BroadcastReceiver() {

    @Inject
    lateinit var callManager: CallManager

    override fun onReceive(context: Context?, intent: Intent?) {
        TODO("Not yet implemented")
    }
}

class PowerButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        TODO("Not yet implemented")
    }
}

class ProximityLockRelease: Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        TODO("Not yet implemented")
    }
}