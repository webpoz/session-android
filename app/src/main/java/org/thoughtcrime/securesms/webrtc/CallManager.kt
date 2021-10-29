package org.thoughtcrime.securesms.webrtc

import android.content.Context
import com.android.mms.transaction.MessageSender
import kotlinx.coroutines.flow.MutableStateFlow
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.dependencies.CallComponent
import org.thoughtcrime.securesms.service.WebRtcCallService
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager
import org.webrtc.*
import java.util.concurrent.Executors
import javax.inject.Inject

class CallManager(private val context: Context): PeerConnection.Observer,
        SignalAudioManager.EventListener,
        CallDataListener {

    enum class CallState {
        STATE_IDLE, STATE_DIALING, STATE_ANSWERING, STATE_REMOTE_RINGING, STATE_LOCAL_RINGING, STATE_CONNECTED
    }


    val signalAudioManager: SignalAudioManager by lazy {
        SignalAudioManager(context, this, CallComponent.get(context).audioManagerCompat())
    }

    private val serviceExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private val eglBase: EglBase = EglBase.create()

    private var peerConnectionWrapper: PeerConnectionWrapper? = null

    private val currentCallState: MutableStateFlow<CallState> = MutableStateFlow(CallState.STATE_IDLE)

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



    fun callEnded() {
        peerConnectionWrapper?.()
        peerConnectionWrapper = null
    }

    fun setAudioEnabled(isEnabled: Boolean) {

    }

    fun setVideoEnabled(isEnabled: Boolean) {

    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
        TODO("Not yet implemented")
    }

    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
        TODO("Not yet implemented")
    }

    override fun onIceConnectionReceivingChange(p0: Boolean) {
        TODO("Not yet implemented")
    }

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
        TODO("Not yet implemented")
    }

    override fun onIceCandidate(p0: IceCandidate?) {
        TODO("Not yet implemented")
    }

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
        TODO("Not yet implemented")
    }

    override fun onAddStream(p0: MediaStream?) {
        TODO("Not yet implemented")
    }

    override fun onRemoveStream(p0: MediaStream?) {
        TODO("Not yet implemented")
    }

    override fun onDataChannel(p0: DataChannel?) {
        TODO("Not yet implemented")
    }

    override fun onRenegotiationNeeded() {
        TODO("Not yet implemented")
    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
        TODO("Not yet implemented")
    }

    override fun onAudioDeviceChanged(activeDevice: SignalAudioManager.AudioDevice, devices: Set<SignalAudioManager.AudioDevice>) {
        TODO("Not yet implemented")
    }

    private fun CallMessage.iceCandidates(): List<IceCandidate> {
        val candidateSize = sdpMids.size
        return (0 until candidateSize).map { i ->
            IceCandidate(sdpMids[i], sdpMLineIndexes[i], sdps[i])
        }
    }

}