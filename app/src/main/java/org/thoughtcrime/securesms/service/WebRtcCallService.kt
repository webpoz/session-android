package org.thoughtcrime.securesms.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.IBinder
import android.os.ResultReceiver
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsession.utilities.FutureTaskListener
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.webrtc.*
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class WebRtcCallService: Service() {

    @Inject lateinit var callManager: CallManager

    companion object {
        const val ACTION_INCOMING_CALL = "CALL_INCOMING"
        const val ACTION_OUTGOING_CALL = "CALL_OUTGOING"
        const val ACTION_ANSWER_CALL = "ANSWER_CALL"
        const val ACTION_DENY_CALL = "DENY_CALL"
        const val ACTION_LOCAL_HANGUP = "LOCAL_HANGUP"
        const val ACTION_SET_MUTE_AUDIO = "SET_MUTE_AUDIO"
        const val ACTION_SET_MUTE_VIDEO = "SET_MUTE_VIDEO"
        const val ACTION_FLIP_CAMERA = "FLIP_CAMERA"
        const val ACTION_UPDATE_AUDIO = "UPDATE_AUDIO"
        const val ACTION_WIRED_HEADSET_CHANGE = "WIRED_HEADSET_CHANGE"
        const val ACTION_SCREEN_OFF = "SCREEN_OFF"
        const val ACTION_CHECK_TIMEOUT = "CHECK_TIMEOUT"
        const val ACTION_IS_IN_CALL_QUERY = "IS_IN_CALL"

        const val ACTION_RESPONSE_MESSAGE = "RESPONSE_MESSAGE"
        const val ACTION_ICE_MESSAGE = "ICE_MESSAGE"
        const val ACTION_ICE_CANDIDATE = "ICE_CANDIDATE"
        const val ACTION_CALL_CONNECTED = "CALL_CONNECTED"
        const val ACTION_REMOTE_HANGUP = "REMOTE_HANGUP"
        const val ACTION_REMOTE_BUSY = "REMOTE_BUSY"
        const val ACTION_REMOTE_VIDEO_MUTE = "REMOTE_VIDEO_MUTE"
        const val ACTION_ICE_CONNECTED = "ICE_CONNECTED"

        const val EXTRA_RECIPIENT_ADDRESS = "RECIPIENT_ID"
        const val EXTRA_ENABLED = "ENABLED"
        const val EXTRA_AUDIO_COMMAND = "AUDIO_COMMAND"
        const val EXTRA_MUTE = "mute_value"
        const val EXTRA_AVAILABLE = "enabled_value"
        const val EXTRA_REMOTE_DESCRIPTION = "remote_description"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_ICE_SDP = "ice_sdp"
        const val EXTRA_ICE_SDP_MID = "ice_sdp_mid"
        const val EXTRA_ICE_SDP_LINE_INDEX = "ice_sdp_line_index"
        const val EXTRA_RESULT_RECEIVER = "result_receiver"

        const val DATA_CHANNEL_NAME = "signaling"

        const val INVALID_NOTIFICATION_ID = -1

        fun acceptCallIntent(context: Context) = Intent(context, WebRtcCallService::class.java).setAction(ACTION_ANSWER_CALL)

        fun denyCallIntent(context: Context) = Intent(context, WebRtcCallService::class.java).setAction(ACTION_DENY_CALL)

        fun hangupIntent(context: Context) = Intent(context, WebRtcCallService::class.java).setAction(ACTION_LOCAL_HANGUP)

        fun sendAudioManagerCommand(context: Context, command: AudioManagerCommand) {
            val intent = Intent(context, WebRtcCallService::class.java)
                .setAction(ACTION_UPDATE_AUDIO)
                .putExtra(EXTRA_AUDIO_COMMAND, command)
            ContextCompat.startForegroundService(context, intent)
        }

        @JvmStatic
        fun isCallActive(context: Context, resultReceiver: ResultReceiver) {
            val intent = Intent(context, WebRtcCallService::class.java)
                    .setAction(ACTION_IS_IN_CALL_QUERY)
                    .putExtra(EXTRA_RESULT_RECEIVER, resultReceiver)
            context.startService(intent)
        }
    }

    private var lastNotificationId: Int = INVALID_NOTIFICATION_ID
    private var lastNotification: Notification? = null

    private val serviceExecutor = Executors.newSingleThreadExecutor()
    private val hangupOnCallAnswered = HangUpRtcOnPstnCallAnsweredListener {
        startService(hangupIntent(this))
    }

    private var callReceiver: IncomingPstnCallReceiver? = null
    private var wiredHeadsetStateReceiver: WiredHeadsetStateReceiver? = null

    @Synchronized
    private fun terminate() {
        stopForeground(true)
        callManager.stop()
    }

    private fun isBusy() = callManager.isBusy(this)

    private fun initializeVideo() {
        callManager.initializeVideo(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == null) return START_NOT_STICKY
        serviceExecutor.execute {
            val action = intent.action
            when {
                action == ACTION_INCOMING_CALL && isBusy() -> handleBusyCall(intent)
                action == ACTION_REMOTE_BUSY -> handleBusyMessage(intent)
                action == ACTION_INCOMING_CALL -> handleIncomingCall(intent)
                action == ACTION_OUTGOING_CALL -> handleOutgoingCall(intent)
                action == ACTION_ANSWER_CALL -> handleAnswerCall(intent)
                action == ACTION_DENY_CALL -> handleDenyCall(intent)
                action == ACTION_LOCAL_HANGUP -> handleLocalHangup(intent)
                action == ACTION_REMOTE_HANGUP -> handleRemoteHangup(intent)
                action == ACTION_SET_MUTE_AUDIO -> handleSetMuteAudio(intent)
                action == ACTION_SET_MUTE_VIDEO -> handleSetMuteVideo(intent)
                action == ACTION_FLIP_CAMERA -> handlesetCameraFlip(intent)
//                action == ACTION_BLUETOOTH_CHANGE -> handleBluetoothChange(intent)
//                action == ACTION_WIRED_HEADSET_CHANGE -> handleWiredHeadsetChange(intent)
                action == ACTION_SCREEN_OFF -> handleScreenOffChange(intent)
                action == ACTION_REMOTE_VIDEO_MUTE -> handleRemoteVideoMute(intent)
                action == ACTION_RESPONSE_MESSAGE -> handleResponseMessage(intent)
                action == ACTION_ICE_MESSAGE -> handleRemoteIceCandidate(intent)
                action == ACTION_ICE_CANDIDATE -> handleLocalIceCandidate(intent)
                action == ACTION_CALL_CONNECTED -> handleCallConnected(intent)
                action == ACTION_CHECK_TIMEOUT -> handleCheckTimeout(intent)
                action == ACTION_IS_IN_CALL_QUERY -> handleIsInCallQuery(intent)
            }
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        callManager.initializeResources(this)
        // create audio manager
        registerIncomingPstnCallReceiver()
        registerWiredHeadsetStateReceiver()
        getSystemService(TelephonyManager::class.java)
                .listen(hangupOnCallAnswered, PhoneStateListener.LISTEN_CALL_STATE)
        // reset call notification
        // register uncaught exception handler
        // register network receiver
        // telephony listen to call state
    }

    private fun registerIncomingPstnCallReceiver() {
        callReceiver = IncomingPstnCallReceiver()
        registerReceiver(callReceiver, IntentFilter("android.intent.action.PHONE_STATE"))
    }

    private fun registerWiredHeadsetStateReceiver() {
        wiredHeadsetStateReceiver = WiredHeadsetStateReceiver()
        registerReceiver(wiredHeadsetStateReceiver, IntentFilter(AudioManager.ACTION_HEADSET_PLUG))
    }

    override fun onDestroy() {
        super.onDestroy()
        callReceiver?.let { receiver ->
            unregisterReceiver(receiver)
        }
        callReceiver = null
        // unregister exception handler
        // shutdown audiomanager
        // unregister network receiver
        // unregister power button
    }

    private class TimeoutRunnable(private val callId: UUID, private val context: Context): Runnable {
        override fun run() {
            val intent = Intent(context, WebRtcCallService::class.java)
                    .setAction(ACTION_CHECK_TIMEOUT)
                    .putExtra(EXTRA_CALL_ID, callId)
            context.startService(intent)
        }
    }

    private abstract class StateAwareListener<V>(
            private val expectedState: CallManager.CallState,
            private val expectedCallId: UUID,
            private val getState: ()->Pair<CallManager.CallState, UUID>): FutureTaskListener<V> {

        companion object {
            private val TAG = Log.tag(StateAwareListener::class.java)
        }

        override fun onSuccess(result: V) {
            if (!isConsistentState()) {
                Log.w(TAG,"State has changed since request, aborting success callback...")
            } else {
                onSuccessContinue(result)
            }
        }

        override fun onFailure(exception: ExecutionException?) {
            if (!isConsistentState()) {
                Log.w(TAG, exception)
                Log.w(TAG,"State has changed since request, aborting failure callback...")
            } else {
                exception?.let {
                    onFailureContinue(it.cause)
                }
            }
        }

        private fun isConsistentState(): Boolean {
            val (currentState, currentCallId) = getState()
            return expectedState == currentState && expectedCallId == currentCallId
        }

        abstract fun onSuccessContinue(result: V)
        abstract fun onFailureContinue(throwable: Throwable?)

    }

}