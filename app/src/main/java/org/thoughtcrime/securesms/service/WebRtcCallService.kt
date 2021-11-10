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
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.FutureTaskListener
import org.session.libsession.utilities.TextSecurePreferences
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
import org.thoughtcrime.securesms.webrtc.locks.LockManager
import org.webrtc.*
import org.webrtc.PeerConnection.IceConnectionState.*
import java.lang.AssertionError
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class WebRtcCallService: Service(), PeerConnection.Observer {

    companion object {

        private val TAG = Log.tag(WebRtcCallService::class.java)

        const val ACTION_INCOMING_RING = "RING_INCOMING"
        const val ACTION_OUTGOING_CALL = "CALL_OUTGOING"
        const val ACTION_ANSWER_CALL = "ANSWER_CALL"
        const val ACTION_DENY_CALL = "DENY_CALL"
        const val ACTION_LOCAL_HANGUP = "LOCAL_HANGUP"
        const val ACTION_SET_MUTE_AUDIO = "SET_MUTE_AUDIO"
        const val ACTION_SET_MUTE_VIDEO = "SET_MUTE_VIDEO"
        const val ACTION_FLIP_CAMERA = "FLIP_CAMERA"
        const val ACTION_BLUETOOTH_CHANGE = "BLUETOOTH_CHANGE"
        const val ACTION_UPDATE_AUDIO = "UPDATE_AUDIO"
        const val ACTION_WIRED_HEADSET_CHANGE = "WIRED_HEADSET_CHANGE"
        const val ACTION_SCREEN_OFF = "SCREEN_OFF"
        const val ACTION_CHECK_TIMEOUT = "CHECK_TIMEOUT"
        const val ACTION_IS_IN_CALL_QUERY = "IS_IN_CALL"

        const val ACTION_RESPONSE_MESSAGE = "RESPONSE_MESSAGE"
        const val ACTION_ICE_MESSAGE = "ICE_MESSAGE"
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

        const val INVALID_NOTIFICATION_ID = -1

        fun cameraEnabled(context: Context, enabled: Boolean) = Intent(context, WebRtcCallService::class.java)
                .setAction(ACTION_SET_MUTE_VIDEO)
                .putExtra(EXTRA_MUTE, !enabled)

        fun flipCamera(context: Context) = Intent(context, WebRtcCallService::class.java)
                .setAction(ACTION_FLIP_CAMERA)

        fun acceptCallIntent(context: Context) = Intent(context, WebRtcCallService::class.java).setAction(ACTION_ANSWER_CALL)

        fun createCall(context: Context, recipient: Recipient) = Intent(context, WebRtcCallService::class.java)
                .setAction(ACTION_OUTGOING_CALL)
                .putExtra(EXTRA_RECIPIENT_ADDRESS, recipient.address)

        fun incomingCall(context: Context, address: Address, sdp: String, callId: UUID, callTime: Long) =
                Intent(context, WebRtcCallService::class.java)
                        .setAction(ACTION_INCOMING_RING)
                        .putExtra(EXTRA_RECIPIENT_ADDRESS, address)
                        .putExtra(EXTRA_CALL_ID, callId)
                        .putExtra(EXTRA_REMOTE_DESCRIPTION, sdp)
                        .putExtra(EXTRA_TIMESTAMP, callTime)

        fun incomingAnswer(context: Context, address: Address, sdp: String, callId: UUID) =
                Intent(context, WebRtcCallService::class.java)
                        .setAction(ACTION_RESPONSE_MESSAGE)
                        .putExtra(EXTRA_RECIPIENT_ADDRESS, address)
                        .putExtra(EXTRA_CALL_ID, callId)
                        .putExtra(EXTRA_REMOTE_DESCRIPTION, sdp)

        fun iceCandidates(context: Context, iceCandidates: List<IceCandidate>, callId: UUID) =
                Intent(context, WebRtcCallService::class.java)
                        .setAction(ACTION_ICE_MESSAGE)
                        .putExtra(EXTRA_CALL_ID, callId)
                        .putExtra(EXTRA_ICE_SDP, iceCandidates.map(IceCandidate::sdp).toTypedArray())
                        .putExtra(EXTRA_ICE_SDP_LINE_INDEX, iceCandidates.map(IceCandidate::sdpMLineIndex).toIntArray())
                        .putExtra(EXTRA_ICE_SDP_MID, iceCandidates.map(IceCandidate::sdpMid).toTypedArray())

        fun denyCallIntent(context: Context) = Intent(context, WebRtcCallService::class.java).setAction(ACTION_DENY_CALL)

        fun remoteHangupIntent(context: Context, callId: UUID) = Intent(context, WebRtcCallService::class.java)
                .setAction(ACTION_REMOTE_HANGUP)
                .putExtra(EXTRA_CALL_ID, callId)

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

    @Inject lateinit var callManager: CallManager

    private var lastNotificationId: Int = INVALID_NOTIFICATION_ID
    private var lastNotification: Notification? = null

    private val lockManager by lazy { LockManager(this) }
    private val serviceExecutor = Executors.newSingleThreadExecutor()
    private val timeoutExecutor = Executors.newScheduledThreadPool(1)
    private val hangupOnCallAnswered = HangUpRtcOnPstnCallAnsweredListener {
        startService(hangupIntent(this))
    }

    private var callReceiver: IncomingPstnCallReceiver? = null
    private var wiredHeadsetStateReceiver: WiredHeadsetStateReceiver? = null
    private var uncaughtExceptionHandlerManager: UncaughtExceptionHandlerManager? = null
    private var powerButtonReceiver: PowerButtonReceiver? = null

    @Synchronized
    private fun terminate() {
        sendBroadcast(Intent(WebRtcCallActivity.ACTION_END))
        callManager.stop()
        stopForeground(true)
    }

    private fun isBusy() = callManager.isBusy(this)

    private fun isIdle() = callManager.isIdle()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == null) return START_NOT_STICKY
        serviceExecutor.execute {
            val action = intent.action
            Log.d("Loki", "Handling ${intent.action}")
            when {
                action == ACTION_INCOMING_RING && isBusy() -> handleBusyCall(intent)
                action == ACTION_REMOTE_BUSY -> handleBusyMessage(intent)
                action == ACTION_INCOMING_RING && isIdle() -> handleIncomingRing(intent)
                action == ACTION_OUTGOING_CALL && isIdle() -> handleOutgoingCall(intent)
                action == ACTION_ANSWER_CALL -> handleAnswerCall(intent)
                action == ACTION_DENY_CALL -> handleDenyCall(intent)
                action == ACTION_LOCAL_HANGUP -> handleLocalHangup(intent)
                action == ACTION_REMOTE_HANGUP -> handleRemoteHangup(intent)
                action == ACTION_SET_MUTE_AUDIO -> handleSetMuteAudio(intent)
                action == ACTION_SET_MUTE_VIDEO -> handleSetMuteVideo(intent)
                action == ACTION_FLIP_CAMERA -> handleSetCameraFlip(intent)
                action == ACTION_BLUETOOTH_CHANGE -> handleBluetoothChange(intent)
                action == ACTION_WIRED_HEADSET_CHANGE -> handleWiredHeadsetChanged(intent)
                action == ACTION_SCREEN_OFF -> handleScreenOffChange(intent)
                action == ACTION_REMOTE_VIDEO_MUTE -> handleRemoteVideoMute(intent)
                action == ACTION_RESPONSE_MESSAGE -> handleResponseMessage(intent)
                action == ACTION_ICE_MESSAGE -> handleRemoteIceCandidate(intent)
                action == ACTION_ICE_CONNECTED -> handleIceConnected(intent)
                action == ACTION_CALL_CONNECTED -> handleCallConnected(intent)
                action == ACTION_CHECK_TIMEOUT -> handleCheckTimeout(intent)
                action == ACTION_IS_IN_CALL_QUERY -> handleIsInCallQuery(intent)
            }
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        callManager.registerListener(this)
        registerIncomingPstnCallReceiver()
        registerWiredHeadsetStateReceiver()
        getSystemService(TelephonyManager::class.java)
                .listen(hangupOnCallAnswered, PhoneStateListener.LISTEN_CALL_STATE)
        registerUncaughtExceptionHandler()
    }

    private fun registerUncaughtExceptionHandler() {
        uncaughtExceptionHandlerManager = UncaughtExceptionHandlerManager().apply {
            registerHandler(ProximityLockRelease(lockManager))
        }
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
        val callState = callManager.currentConnectionState

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (callState) {
                STATE_DIALING,
                STATE_REMOTE_RINGING -> setCallInProgressNotification(TYPE_OUTGOING_RINGING, callManager.recipient)
                STATE_IDLE -> setCallInProgressNotification(TYPE_INCOMING_CONNECTING, recipient)
                STATE_ANSWERING -> setCallInProgressNotification(TYPE_INCOMING_CONNECTING, callManager.recipient)
                STATE_LOCAL_RINGING -> setCallInProgressNotification(TYPE_INCOMING_RINGING, callManager.recipient)
                STATE_CONNECTED -> setCallInProgressNotification(TYPE_ESTABLISHED, callManager.recipient)
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
        if (callManager.currentConnectionState != STATE_DIALING || callManager.callId != callId || callManager.recipient != recipient) {
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

    private fun handleIncomingRing(intent: Intent) {
        if (callManager.currentConnectionState != STATE_IDLE) throw IllegalStateException("Incoming ring on non-idle")

        val callId = getCallId(intent)
        val recipient = getRemoteRecipient(intent)
        val offer = intent.getStringExtra(EXTRA_REMOTE_DESCRIPTION) ?: return
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setCallInProgressNotification(TYPE_INCOMING_RINGING, recipient)
        }
        callManager.onIncomingRing(offer, callId, recipient, timestamp)
        callManager.clearPendingIceUpdates()
        callManager.postConnectionEvent(STATE_LOCAL_RINGING)
        if (TextSecurePreferences.isCallNotificationsEnabled(this)) {
            callManager.startIncomingRinger()
        }
        registerPowerButtonReceiver()
    }

    private fun handleOutgoingCall(intent: Intent) {
        if (callManager.currentConnectionState != STATE_IDLE) throw IllegalStateException("Dialing from non-idle")

        callManager.postConnectionEvent(STATE_DIALING)
        callManager.recipient = getRemoteRecipient(intent)
        val callId = UUID.randomUUID()
        callManager.callId = callId

        callManager.initializeVideo(this)

        callManager.postViewModelState(CallViewModel.State.CALL_OUTGOING)
        lockManager.updatePhoneState(LockManager.PhoneState.IN_CALL)
        callManager.initializeAudioForCall()
        callManager.startOutgoingRinger(OutgoingRinger.Type.RINGING)
        setCallInProgressNotification(TYPE_OUTGOING_RINGING, callManager.recipient)
        // TODO: DatabaseComponent.get(this).insertOutgoingCall(callManager.recipient!!.address)
        timeoutExecutor.schedule(TimeoutRunnable(callId, this), 5, TimeUnit.MINUTES)

        val expectedState = callManager.currentConnectionState
        val expectedCallId = callManager.callId

        try {
            val offerFuture = callManager.onOutgoingCall(this)
            offerFuture.fail { e ->
                if (isConsistentState(expectedState, expectedCallId, callManager.currentConnectionState, callManager.callId)) {
                    Log.e(TAG,e)
                    callManager.postViewModelState(CallViewModel.State.NETWORK_FAILURE)
                    terminate()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG,e)
            terminate()
        }
    }

    private fun handleAnswerCall(intent: Intent) {
        if (callManager.currentConnectionState != STATE_LOCAL_RINGING) {
            Log.e(TAG,"Can only answer from ringing!")
            return
        }

        val pending = callManager.pendingOffer ?: return
        val callId = callManager.callId ?: return
        val recipient = callManager.recipient ?: return
        val timestamp = callManager.pendingOfferTime

        intent.putExtra(EXTRA_CALL_ID, callId)
        intent.putExtra(EXTRA_RECIPIENT_ADDRESS, recipient.address)
        intent.putExtra(EXTRA_REMOTE_DESCRIPTION, pending)
        intent.putExtra(EXTRA_TIMESTAMP, timestamp)

        callManager.postConnectionEvent(STATE_ANSWERING)

        if (isIncomingMessageExpired(intent)) {
            insertMissedCall(recipient, true)
            terminate()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setCallInProgressNotification(TYPE_INCOMING_CONNECTING, recipient)
        }

        timeoutExecutor.schedule(TimeoutRunnable(callId, this), 5, TimeUnit.MINUTES)

        callManager.initializeAudioForCall()
        callManager.initializeVideo(this)

        val expectedState = callManager.currentConnectionState
        val expectedCallId = callManager.callId

        try {
            val answerFuture = callManager.onIncomingCall(this) // add is always turn here
            answerFuture.fail { e ->
                if (isConsistentState(expectedState,expectedCallId, callManager.currentConnectionState, callManager.callId)) {
                    Log.e(TAG, e)
                    insertMissedCall(recipient, true)
                    terminate()
                }
            }
            callManager.postViewModelState(CallViewModel.State.CALL_INCOMING)
            lockManager.updatePhoneState(LockManager.PhoneState.PROCESSING)
        } catch (e: Exception) {
            Log.e(TAG,e)
            terminate()
        }
        // DatabaseComponent.get(this).smsDatabase().insertReceivedCall(recipient)
    }

    private fun handleDenyCall(intent: Intent) {
        if (callManager.currentConnectionState != STATE_LOCAL_RINGING) {
            Log.e(TAG,"Can only deny from ringing!")
            return
        }

        callManager.handleDenyCall()
        // DatabaseComponent.get(this).smsDatabase().insertMissedCall(recipient)
        terminate()
    }

    private fun handleLocalHangup(intent: Intent) {
        callManager.handleLocalHangup()
        terminate()
    }

    private fun handleRemoteHangup(intent: Intent) {
        if (callManager.callId != getCallId(intent)) {
            Log.e(TAG, "Hangup for non-active call...")
            terminate()
            return
        }

        callManager.handleRemoteHangup()

        if (callManager.currentConnectionState in arrayOf(STATE_REMOTE_RINGING, STATE_ANSWERING, STATE_LOCAL_RINGING)) {
            callManager.recipient?.let { recipient ->
                insertMissedCall(recipient, true)
            }
        }
        terminate()
    }

    private fun handleSetMuteAudio(intent: Intent) {
        val muted = intent.getBooleanExtra(EXTRA_MUTE, false)
        callManager.handleSetMuteAudio(muted)
    }

    private fun handleSetMuteVideo(intent: Intent) {
        val muted = intent.getBooleanExtra(EXTRA_MUTE, false)
        callManager.handleSetMuteVideo(muted, lockManager)
    }

    private fun handleSetCameraFlip(intent: Intent) {
        callManager.handleSetCameraFlip()
    }

    private fun handleBluetoothChange(intent: Intent) {
        val bluetoothAvailable = intent.getBooleanExtra(EXTRA_AVAILABLE, false)
        callManager.postBluetoothAvailable(bluetoothAvailable)
    }

    private fun handleWiredHeadsetChanged(intent: Intent) {
        callManager.handleWiredHeadsetChanged(intent.getBooleanExtra(EXTRA_AVAILABLE, false))
    }

    private fun handleScreenOffChange(intent: Intent) {
        callManager.handleScreenOffChange()
    }

    private fun handleRemoteVideoMute(intent: Intent) {
        val muted = intent.getBooleanExtra(EXTRA_MUTE, false)
        val callId = intent.getSerializableExtra(EXTRA_CALL_ID) as UUID

        callManager.handleRemoteVideoMute(muted, callId)
    }


    private fun handleResponseMessage(intent: Intent) {
        try {
            val recipient = getRemoteRecipient(intent)
            val callId = getCallId(intent)
            val description = intent.getStringExtra(EXTRA_REMOTE_DESCRIPTION)
            callManager.handleResponseMessage(recipient, callId, SessionDescription(SessionDescription.Type.ANSWER, description))
        } catch (e: PeerConnectionException) {
            terminate()
        }
    }

    private fun handleRemoteIceCandidate(intent: Intent) {
        val callId = getCallId(intent)
        val sdpMids = intent.getStringArrayExtra(EXTRA_ICE_SDP_MID) ?: return
        val sdpLineIndexes = intent.getIntArrayExtra(EXTRA_ICE_SDP_LINE_INDEX) ?: return
        val sdps = intent.getStringArrayExtra(EXTRA_ICE_SDP) ?: return
        if (sdpMids.size != sdpLineIndexes.size || sdpLineIndexes.size != sdps.size) {
            Log.w(TAG,"sdp info not of equal length")
            return
        }
        val iceCandidates = (0 until sdpMids.size).map { index ->
            IceCandidate(
                    sdpMids[index],
                    sdpLineIndexes[index],
                    sdps[index]
            )
        }
        callManager.handleRemoteIceCandidate(iceCandidates, callId)
    }

    private fun handleIceConnected(intent: Intent) {
        val recipient = callManager.recipient ?: return
        if (callManager.currentConnectionState in arrayOf(STATE_ANSWERING, STATE_LOCAL_RINGING)) {
            callManager.postConnectionEvent(STATE_CONNECTED)
            callManager.postViewModelState(CallViewModel.State.CALL_CONNECTED)
        } else {
            Log.w(TAG, "Got ice connected out of state")
        }

        setCallInProgressNotification(TYPE_ESTABLISHED, recipient)

        callManager.startCommunication(lockManager)
    }

    private fun handleCallConnected(intent: Intent) {

    }

    private fun handleIsInCallQuery(intent: Intent) {

    }

    private fun registerPowerButtonReceiver() {
        if (powerButtonReceiver == null) {
            powerButtonReceiver = PowerButtonReceiver()

            registerReceiver(powerButtonReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
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
        callManager.unregisterListener(this)
        callReceiver?.let { receiver ->
            unregisterReceiver(receiver)
        }
        callReceiver = null
        uncaughtExceptionHandlerManager?.unregister()
        callManager.onDestroy()
        super.onDestroy()
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

    private abstract class FailureListener<V>(
            expectedState: CallManager.CallState,
            expectedCallId: UUID?,
            getState: () -> Pair<CallManager.CallState, UUID?>): StateAwareListener<V>(expectedState, expectedCallId, getState) {
        override fun onSuccessContinue(result: V) {}
    }

    private abstract class SuccessOnlyListener<V>(
            expectedState: CallManager.CallState,
            expectedCallId: UUID?,
            getState: () -> Pair<CallManager.CallState, UUID>): StateAwareListener<V>(expectedState, expectedCallId, getState) {
        override fun onFailureContinue(throwable: Throwable?) {
            Log.e(TAG, throwable)
            throw AssertionError(throwable)
        }
    }

    private abstract class StateAwareListener<V>(
            private val expectedState: CallManager.CallState,
            private val expectedCallId: UUID?,
            private val getState: ()->Pair<CallManager.CallState, UUID?>): FutureTaskListener<V> {

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

    private fun isConsistentState(
            expectedState: CallManager.CallState,
            expectedCallId: UUID?,
            currentState: CallManager.CallState,
            currentCallId: UUID?
    ): Boolean {
        return expectedState == currentState && expectedCallId == currentCallId
    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        if (newState in arrayOf(CONNECTED, COMPLETED)) {
            val intent = Intent(this, WebRtcCallService::class.java)
                    .setAction(ACTION_ICE_CONNECTED)
            startService(intent)
        } else if (newState == FAILED) {
            val intent = Intent(this, WebRtcCallService::class.java)
                    .setAction(ACTION_REMOTE_HANGUP)
                    .putExtra(EXTRA_CALL_ID, callManager.callId)

            startService(intent)
        }
    }

    override fun onIceConnectionReceivingChange(p0: Boolean) {}

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}

    override fun onIceCandidate(p0: IceCandidate?) {}

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}

    override fun onAddStream(p0: MediaStream?) {}

    override fun onRemoveStream(p0: MediaStream?) {}

    override fun onDataChannel(p0: DataChannel?) {}

    override fun onRenegotiationNeeded() {
        Log.w(TAG,"onRenegotiationNeeded was called!")
    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
}