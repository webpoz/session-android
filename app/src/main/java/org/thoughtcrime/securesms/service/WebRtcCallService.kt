package org.thoughtcrime.securesms.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class WebRtcCallService: Service() {

    companion object {
        private const val ACTION_UPDATE = "UPDATE"
        private const val ACTION_STOP = "STOP"
        private const val ACTION_DENY_CALL = "DENY_CALL"
        private const val ACTION_LOCAL_HANGUP = "LOCAL_HANGUP"
        private const val ACTION_WANTS_BLUETOOTH = "WANTS_BLUETOOTH"
        private const val ACTION_CHANGE_POWER_BUTTON = "CHANGE_POWER_BUTTON"

        private const val EXTRA_UPDATE_TYPE = "UPDATE_TYPE"
        private const val EXTRA_RECIPIENT_ID = "RECIPIENT_ID"
        private const val EXTRA_ENABLED = "ENABLED"

        private const val INVALID_NOTIFICATION_ID = -1
    }

    override fun onBind(intent: Intent?): IBinder? = null

}