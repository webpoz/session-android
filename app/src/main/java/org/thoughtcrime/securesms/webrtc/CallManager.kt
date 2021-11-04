package org.thoughtcrime.securesms.webrtc

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import nl.komponents.kovenant.Promise
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.utilities.Util
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.webrtc.audio.AudioManagerCompat
import org.thoughtcrime.securesms.webrtc.audio.OutgoingRinger
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager
import org.thoughtcrime.securesms.webrtc.video.CameraEventListener
import org.thoughtcrime.securesms.webrtc.video.CameraState
import org.webrtc.*
import java.lang.NullPointerException
import java.util.*
import java.util.concurrent.Executors

class CallManager(context: Context, audioManager: AudioManagerCompat): PeerConnection.Observer,
        SignalAudioManager.EventListener,
        CallDataListener, CameraEventListener, DataChannel.Observer {

    enum class CallState {
        STATE_IDLE, STATE_DIALING, STATE_ANSWERING, STATE_REMOTE_RINGING, STATE_LOCAL_RINGING, STATE_CONNECTED
    }

    sealed class StateEvent {
        data class AudioEnabled(val isEnabled: Boolean): StateEvent()
        data class VideoEnabled(val isEnabled: Boolean): StateEvent()
        data class CallStateUpdate(val state: CallState): StateEvent()
    }

    companion object {
        private val TAG = Log.tag(CallManager::class.java)
        val CONNECTED_STATES = arrayOf(CallState.STATE_CONNECTED)
        val PENDING_CONNECTION_STATES = arrayOf(
                CallState.STATE_DIALING,
                CallState.STATE_ANSWERING,
                CallState.STATE_LOCAL_RINGING,
                CallState.STATE_REMOTE_RINGING
        )
        val OUTGOING_STATES = arrayOf(
                CallState.STATE_DIALING,
                CallState.STATE_REMOTE_RINGING,
                CallState.STATE_CONNECTED
        )
        val DISCONNECTED_STATES = arrayOf(CallState.STATE_IDLE)
        private const val DATA_CHANNEL_NAME = "signaling"
    }


    private val signalAudioManager: SignalAudioManager = SignalAudioManager(context, this, audioManager)

    private val _audioEvents = MutableStateFlow(StateEvent.AudioEnabled(false))
    val audioEvents = _audioEvents.asSharedFlow()
    private val _videoEvents = MutableStateFlow(StateEvent.VideoEnabled(false))
    val videoEvents = _videoEvents.asSharedFlow()
    private val _remoteVideoEvents = MutableStateFlow(StateEvent.VideoEnabled(false))
    val remoteVideoEvents = _remoteVideoEvents.asSharedFlow()
    private val _connectionEvents = MutableStateFlow<StateEvent>(StateEvent.CallStateUpdate(CallState.STATE_IDLE))
    val connectionEvents = _connectionEvents.asSharedFlow()
    private val _callStateEvents = MutableStateFlow(CallViewModel.State.CALL_PENDING)
    val callStateEvents = _callStateEvents.asSharedFlow()
    private var localCameraState: CameraState = CameraState.UNKNOWN
    private var bluetoothAvailable = false

    val currentConnectionState = (_connectionEvents.value as StateEvent.CallStateUpdate).state

    private val networkExecutor = Executors.newSingleThreadExecutor()

    private var eglBase: EglBase? = null

    var callId: UUID? = null
    var recipient: Recipient? = null

    fun getCurrentCallState(): Pair<CallState, UUID?> = currentConnectionState to callId

    private var peerConnection: PeerConnectionWrapper? = null
    private var dataChannel: DataChannel? = null

    private val pendingOutgoingIceUpdates = ArrayDeque<IceCandidate>()
    private val pendingIncomingIceUpdates = ArrayDeque<IceCandidate>()

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null

    fun clearPendingIceUpdates() {
        pendingOutgoingIceUpdates.clear()
        pendingIncomingIceUpdates.clear()
    }

    fun initializeAudioForCall() {
        signalAudioManager.initializeAudioForCall()
    }

    fun startOutgoingRinger(ringerType: OutgoingRinger.Type) {
        signalAudioManager.startOutgoingRinger(ringerType)
    }

    fun postConnectionEvent(newState: CallState) {
        _connectionEvents.value = StateEvent.CallStateUpdate(newState)
    }

    fun postViewModelState(newState: CallViewModel.State) {
        _callStateEvents.value = newState
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): CameraVideoCapturer? {
        val deviceNames = enumerator.deviceNames

        // First, try to find front facing camera
        Log.d("Loki-RTC-vid", "Looking for front facing cameras.")
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Log.d("Loki-RTC-vid", "Creating front facing camera capturer.")
                val videoCapturer = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        // Front facing camera not found, try something else
        Log.d("Loki-RTC-vid", "Looking for other cameras.")
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Log.d("Loki-RTC-vid", "Creating other camera capturer.")
                val videoCapturer = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        return null
    }

    override fun newCallMessage(callMessage: SignalServiceProtos.CallMessage) {

    }

    fun networkChange(networkAvailable: Boolean) {

    }

    fun acceptCall() {

    }

    fun declineCall() {

    }

    fun isBusy(context: Context) = currentConnectionState != CallState.STATE_IDLE
            || context.getSystemService(TelephonyManager::class.java).callState  != TelephonyManager.CALL_STATE_IDLE

    fun initializeVideo(context: Context) {
        Util.runOnMainSync {
            val base = EglBase.create()
            eglBase = base
            localRenderer = SurfaceViewRenderer(context)
            remoteRenderer = SurfaceViewRenderer(context)

            localRenderer?.init(base.eglBaseContext, null)
            remoteRenderer?.init(base.eglBaseContext, null)

            val encoderFactory = DefaultVideoEncoderFactory(base.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(base.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(object: PeerConnectionFactory.Options() {
                        init {
                            networkIgnoreMask = 1 shl 4
                        }
                    })
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory()
        }
    }

    fun callEnded() {
        peerConnection?.dispose()
        peerConnection = null
    }

    fun setAudioEnabled(isEnabled: Boolean) {
        currentConnectionState.withState(*(CONNECTED_STATES + PENDING_CONNECTION_STATES)) {
            peerConnection?.setAudioEnabled(isEnabled)
            _audioEvents.value = StateEvent.AudioEnabled(true)
        }
    }

    fun setVideoEnabled(isEnabled: Boolean) {
        currentConnectionState.withState(*(CONNECTED_STATES + PENDING_CONNECTION_STATES)) {
            peerConnection?.setVideoEnabled(isEnabled)
            _audioEvents.value = StateEvent.AudioEnabled(true)
        }
    }

    override fun onSignalingChange(newState: PeerConnection.SignalingState) {

    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {

    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {

    }

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {

    }

    override fun onIceCandidate(p0: IceCandidate?) {

    }

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {

    }

    override fun onAddStream(p0: MediaStream?) {

    }

    override fun onRemoveStream(p0: MediaStream?) {

    }

    override fun onDataChannel(p0: DataChannel?) {

    }

    override fun onRenegotiationNeeded() {

    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {

    }

    override fun onBufferedAmountChange(l: Long) {
        Log.i(TAG,"onBufferedAmountChange: $l")
    }

    override fun onStateChange() {
        Log.i(TAG,"onStateChange")
    }

    override fun onMessage(buffer: DataChannel.Buffer?) {
        Log.i(TAG,"onMessage...")
        TODO("interpret the data channel buffer and check for signals")
    }

    override fun onAudioDeviceChanged(activeDevice: SignalAudioManager.AudioDevice, devices: Set<SignalAudioManager.AudioDevice>) {
        signalAudioManager.handleCommand(AudioManagerCommand())
    }

    private fun CallMessage.iceCandidates(): List<IceCandidate> {
        val candidateSize = sdpMids.size
        return (0 until candidateSize).map { i ->
            IceCandidate(sdpMids[i], sdpMLineIndexes[i], sdps[i])
        }
    }

    private fun CallState.withState(vararg expected: CallState, transition: () -> Unit) {
        if (this in expected) transition()
        else Log.w(TAG,"Tried to transition state $this but expected $expected")
    }

    fun stop() {
        signalAudioManager.stop(currentConnectionState in OUTGOING_STATES)
        peerConnection?.dispose()
        peerConnection = null

        localRenderer?.release()
        remoteRenderer?.release()
        eglBase?.release()

        localRenderer = null
        remoteRenderer = null
        eglBase = null

        _connectionEvents.value = StateEvent.CallStateUpdate(CallState.STATE_IDLE)
        localCameraState = CameraState.UNKNOWN
        recipient = null
        callId = null
        microphoneEnabled = true
        remoteVideoEnabled = false
        pendingOutgoingIceUpdates.clear()
        pendingIncomingIceUpdates.clear()
    }

    override fun onCameraSwitchCompleted(newCameraState: CameraState) {
        localCameraState = newCameraState
    }

    fun onIncomingCall(offer: String, context: Context, isAlwaysTurn: Boolean = false): Promise<Unit, Exception> {
        val callId = callId ?: return Promise.ofFail(NullPointerException("callId is null"))
        val recipient = recipient ?: return Promise.ofFail(NullPointerException("recipient is null"))
        val factory = peerConnectionFactory ?: return Promise.ofFail(NullPointerException("peerConnectionFactory is null"))
        val local = localRenderer ?: return Promise.ofFail(NullPointerException("localRenderer is null"))
        val base = eglBase ?: return Promise.ofFail(NullPointerException("eglBase is null"))
        val connection = PeerConnectionWrapper(
                context,
                factory,
                this,
                local,
                this,
                base,
                isAlwaysTurn
        )
        peerConnection = connection
        localCameraState = connection.getCameraState()
        connection.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, offer))
        val answer = connection.createAnswer(MediaConstraints())
        connection.setLocalDescription(answer)

        val answerMessage = MessageSender.sendNonDurably(CallMessage.answer(
                answer.description,
                callId
        ), recipient.address)

        while (pendingIncomingIceUpdates.isNotEmpty()) {
            val candidate = pendingIncomingIceUpdates.pop() ?: break
            connection.addIceCandidate(candidate)
        }
        return answerMessage // TODO: maybe add success state update
    }

    fun onOutgoingCall(context: Context, isAlwaysTurn: Boolean = false): Promise<Unit, Exception> {
        val callId = callId ?: return Promise.ofFail(NullPointerException("callId is null"))
        val recipient = recipient
                ?: return Promise.ofFail(NullPointerException("recipient is null"))
        val factory = peerConnectionFactory
                ?: return Promise.ofFail(NullPointerException("peerConnectionFactory is null"))
        val local = localRenderer
                ?: return Promise.ofFail(NullPointerException("localRenderer is null"))
        val base = eglBase ?: return Promise.ofFail(NullPointerException("eglBase is null"))

        val connection = PeerConnectionWrapper(
                context,
                factory,
                this,
                local,
                this,
                base,
                isAlwaysTurn
        )

        localCameraState = connection.getCameraState()
        val dataChannel = connection.createDataChannel(DATA_CHANNEL_NAME)
        dataChannel.registerObserver(this)
        val offer = connection.createOffer(MediaConstraints())
        connection.setLocalDescription(offer)

        Log.i(TAG, "Sending offer: ${offer.description}")

        return MessageSender.sendNonDurably(CallMessage.offer(
                offer.description,
                callId
        ), recipient.address)
    }

    fun callNotSetup(): Boolean =
            peerConnection == null || dataChannel == null || recipient == null || callId == null

    fun handleAnswerCall(): Pair<UUID, Recipient> {
        peerConnection?.let { connection ->
            connection.setAudioEnabled(true)
            connection.setVideoEnabled(true)
        }
        return callId!! to recipient!!
    }

    fun handleDenyCall() {
        val callId = callId ?: return
        val recipient = recipient ?: return
        MessageSender.sendNonDurably(CallMessage.endCall(callId), recipient.address)
    }

    fun handleLocalHangup() {
        val recipient = recipient ?: return
        val callId = callId ?: return

        postViewModelState(CallViewModel.State.CALL_DISCONNECTED)
        MessageSender.sendNonDurably(CallMessage.endCall(callId), recipient.address)
    }

    fun handleRemoteHangup() {
        when (currentConnectionState) {
            CallState.STATE_DIALING,
            CallState.STATE_REMOTE_RINGING -> postViewModelState(CallViewModel.State.RECIPIENT_UNAVAILABLE)
            else -> postViewModelState(CallViewModel.State.CALL_DISCONNECTED)
        }
    }

    fun handleSetMuteAudio(muted: Boolean) {
        _audioEvents.value = StateEvent.AudioEnabled(!muted)
        peerConnection?.setAudioEnabled(_audioEvents.value.isEnabled)
    }

    fun handleSetMuteVideo(muted: Boolean) {
        _videoEvents.value = StateEvent.VideoEnabled(!muted)
        peerConnection?.setVideoEnabled(_videoEvents.value.isEnabled)
        TODO()
    }

}