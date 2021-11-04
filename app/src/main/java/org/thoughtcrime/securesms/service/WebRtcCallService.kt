package org.thoughtcrime.securesms.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.ResultReceiver
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.FutureTaskListener
import org.session.libsession.utilities.Util
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.calls.WebRtcCallActivity
import org.thoughtcrime.securesms.util.CallNotificationBuilder
import org.thoughtcrime.securesms.util.CallNotificationBuilder.Companion.TYPE_ESTABLISHED
import org.thoughtcrime.securesms.util.CallNotificationBuilder.Companion.TYPE_INCOMING_CONNECTING
import org.thoughtcrime.securesms.util.CallNotificationBuilder.Companion.TYPE_INCOMING_RINGING
import org.thoughtcrime.securesms.util.CallNotificationBuilder.Companion.TYPE_OUTGOING_RINGING
import org.thoughtcrime.securesms.webrtc.*
import org.thoughtcrime.securesms.webrtc.CallManager.CallState.*
import org.thoughtcrime.securesms.webrtc.audio.OutgoingRinger
import java.lang.AssertionError
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class WebRtcCallService: Service() {

    @Inject lateinit var callManager: CallManager

    companion object {

        private val TAG = Log.tag(WebRtcCallService::class.java)

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

    private fun handleBusyCall(intent: Intent) {
        val recipient = getRemoteRecipient(intent)
        val callId = getCallId(intent)
        val callState = callManager.currentConnectionState

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (callState) {
                STATE_DIALING,
                STATE_REMOTE_RINGING -> setCallInProgressNotification(TYPE_OUTGOING_RINGING, callManager.recipient)
                STATE_IDLE -> setCallInProgressNotification(TYPE_INCOMING_CONNECTING, recipient)
                STATE_ANSWERING -> setCallInProgressNotification(TYPE_INCOMING_CONNECTING, callManager.recipient)
                STATE_LOCAL_RINGING -> setCallInProgressNotification(TYPE_INCOMING_RINGING, callManager.recipient)
                STATE_CONNECTED -> setCallInProgressNotification(TYPE_ESTABLISHED, callManager.recipient)
                else -> throw AssertionError()
            }
        }

        if (callState == STATE_IDLE) {
            stopForeground(true)
        }

        // TODO: send hangup via messageSender
        insertMissedCall(getRemoteRecipient(intent), false)
    }

    private fun handleBusyMessage(intent: Intent) {
        val recipient = getRemoteRecipient(intent)
        val callId = getCallId(intent)
        if (callManager.currentConnectionState != STATE_DIALING || callManager.callId != callManager.callId || callManager.recipient != callManager.recipient) {
            Log.w(TAG,"Got busy message for inactive session...")
            return
        }
        callManager.postViewModelState(CallViewModel.State.CALL_BUSY)
        callManager.startOutgoingRinger(OutgoingRinger.Type.BUSY)
        Util.runOnMainDelayed({
            startService(
                    Intent(this, WebRtcCallService::class.java)
                            .setAction(ACTION_LOCAL_HANGUP)
            )
        }, WebRtcCallActivity.BUSY_SIGNAL_DELAY_FINISH)
    }

    private fun handleIncomingCall(intent: Intent) {
        if (callManager.currentConnectionState != STATE_IDLE) throw IllegalStateException("Incoming on non-idle")

        val offer = intent.getStringExtra(EXTRA_REMOTE_DESCRIPTION)
        callManager.postConnectionEvent(STATE_ANSWERING)
        callManager.callId = getCallId(intent)
        callManager.clearPendingIceUpdates()
        val recipient = getRemoteRecipient(intent)
        callManager.recipient = recipient
        if (isIncomingMessageExpired(intent)) {
            insertMissedCall(recipient, true)
        }
    }

    private fun handleCheckTimeout(intent: Intent) {
        val callId = callManager.callId ?: return
        val callState = callManager.currentConnectionState

        if (callId == getCallId(intent) && callState != STATE_CONNECTED) {
            Log.w(TAG, "Timing out call: $callId")
            callManager.postViewModelState(CallViewModel.State.CALL_DISCONNECTED)
        }
    }

    private fun setCallInProgressNotification(type: Int, recipient: Recipient?) {
        startForeground(
                CallNotificationBuilder.WEBRTC_NOTIFICATION,
                CallNotificationBuilder.getCallInProgressNotification(this, type, recipient)
        )
    }

    private fun getRemoteRecipient(intent: Intent): Recipient {
        val remoteAddress = intent.getParcelableExtra<Address>(EXTRA_RECIPIENT_ADDRESS)
                ?: throw AssertionError("No recipient in intent!")

        return Recipient.from(this, remoteAddress, true)
    }

    private fun getCallId(intent: Intent) : UUID {
        return intent.getSerializableExtra(EXTRA_CALL_ID) as? UUID
                ?: throw AssertionError("No callId in intent!")
    }

    private fun insertMissedCall(recipient: Recipient, signal: Boolean) {
        // TODO
//        val messageAndThreadId = DatabaseComponent.get(this).smsDatabase().insertReceivedCall(recipient.address)
//        MessageNotifier.updateNotification(this, messageAndThreadId.second, signal)
    }

    private fun isIncomingMessageExpired(intent: Intent) =
            System.currentTimeMillis() - intent.getLongExtra(EXTRA_TIMESTAMP, -1) > TimeUnit.MINUTES.toMillis(2)

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