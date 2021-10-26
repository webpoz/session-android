package org.thoughtcrime.securesms.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.webrtc.AudioManagerCommand
import org.thoughtcrime.securesms.webrtc.CallManager
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class WebRtcCallService: Service() {

    @Inject lateinit var callManager: CallManager

    companion object {
        private const val ACTION_UPDATE = "UPDATE"
        private const val ACTION_STOP = "STOP"
        private const val ACTION_DENY_CALL = "DENY_CALL"
        private const val ACTION_LOCAL_HANGUP = "LOCAL_HANGUP"
        private const val ACTION_WANTS_BLUETOOTH = "WANTS_BLUETOOTH"
        private const val ACTION_CHANGE_POWER_BUTTON = "CHANGE_POWER_BUTTON"
        private const val ACTION_SEND_AUDIO_COMMAND = "SEND_AUDIO_COMMAND"

        private const val EXTRA_UPDATE_TYPE = "UPDATE_TYPE"
        private const val EXTRA_RECIPIENT_ID = "RECIPIENT_ID"
        private const val EXTRA_ENABLED = "ENABLED"
        private const val EXTRA_AUDIO_COMMAND = "AUDIO_COMMAND"

        private const val INVALID_NOTIFICATION_ID = -1

        fun update(context: Context, type: Int, callId: UUID) {
            val intent = Intent(context, WebRtcCallService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_RECIPIENT_ID, callId)
                .putExtra(EXTRA_UPDATE_TYPE, type)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WebRtcCallService::class.java)
                .setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }

        fun denyCallIntent(context: Context) = Intent(context, WebRtcCallService::class.java).setAction(ACTION_DENY_CALL)

        fun hangupIntent(context: Context) = Intent(context, WebRtcCallService::class.java).setAction(ACTION_LOCAL_HANGUP)

        fun sendAudioManagerCommand(context: Context, command: AudioManagerCommand) {
            val intent = Intent(context, WebRtcCallService::class.java)
                .setAction(ACTION_SEND_AUDIO_COMMAND)
                .putExtra(EXTRA_AUDIO_COMMAND, command)
            ContextCompat.startForegroundService(context, intent)
        }

        fun changePowerButtonReceiver(context: Context, register: Boolean) {
            val intent = Intent(context, WebRtcCallService::class.java)
                .setAction(ACTION_CHANGE_POWER_BUTTON)
                .putExtra(EXTRA_ENABLED, register)
            ContextCompat.startForegroundService(context, intent)
        }

    }

    override fun onBind(intent: Intent?): IBinder? = null

}