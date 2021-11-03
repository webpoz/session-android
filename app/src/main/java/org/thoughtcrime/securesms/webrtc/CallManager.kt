package org.thoughtcrime.securesms.webrtc

import android.content.Context
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.utilities.Util
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.service.WebRtcCallService
import org.thoughtcrime.securesms.webrtc.audio.AudioManagerCompat
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager
import org.thoughtcrime.securesms.webrtc.video.CameraState
import org.webrtc.*
import java.util.*
import java.util.concurrent.Executors

class CallManager(context: Context, audioManager: AudioManagerCompat): PeerConnection.Observer,
        SignalAudioManager.EventListener,
        CallDataListener {

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
    private var localCameraState: CameraState = CameraState.UNKNOWN
    private var microphoneEnabled = true
    private var remoteVideoEnabled = false
    private var bluetoothAvailable = false

    private val currentCallState = (_connectionEvents.value as StateEvent.CallStateUpdate).state

    private val networkExecutor = Executors.newSingleThreadExecutor()

    private var eglBase: EglBase? = null

    private var callId: UUID? = null
    private var recipient: Recipient? = null
    private var peerConnectionWrapper: PeerConnectionWrapper? = null
    private var dataChannel: DataChannel? = null

    private val pendingOutgoingIceUpdates = ArrayDeque<IceCandidate>()
    private val pendingIncomingIceUpdates = ArrayDeque<IceCandidate>()

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null

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

    fun isBusy(context: Context) = currentCallState != CallState.STATE_IDLE
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
        peerConnectionWrapper?.dispose()
        peerConnectionWrapper = null
    }

    fun setAudioEnabled(isEnabled: Boolean) {
        currentCallState.withState(*(CONNECTED_STATES + PENDING_CONNECTION_STATES)) {
            peerConnectionWrapper?.setAudioEnabled(isEnabled)
            _audioEvents.value = StateEvent.AudioEnabled(true)
        }
    }

    fun setVideoEnabled(isEnabled: Boolean) {
        currentCallState.withState(*(CONNECTED_STATES + PENDING_CONNECTION_STATES)) {
            peerConnectionWrapper?.setVideoEnabled(isEnabled)
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

    override fun onAudioDeviceChanged(activeDevice: SignalAudioManager.AudioDevice, devices: Set<SignalAudioManager.AudioDevice>) {
        signalAudioManager.handleCommand(AudioManagerCommand())
    }

    private fun CallMessage.iceCandidates(): List<IceCandidate> {
        val candidateSize = sdpMids.size
        return (0 until candidateSize).map { i ->
            IceCandidate(sdpMids[i], sdpMLineIndexes[i], sdps[i])
        }
    }

    private fun CallState.withState(vararg expected: CallState, transition: ()->Unit) {
        if (this in expected) transition()
        else Log.w(TAG,"Tried to transition state $this but expected $expected")
    }

    fun stop() {
        signalAudioManager.stop(currentCallState in OUTGOING_STATES)
        peerConnectionWrapper?.dispose()
        peerConnectionWrapper = null

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

    fun initializeResources(webRtcCallService: WebRtcCallService) {
        TODO("Not yet implemented")
    }

}