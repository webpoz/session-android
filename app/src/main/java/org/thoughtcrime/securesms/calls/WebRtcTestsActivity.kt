package org.thoughtcrime.securesms.calls

import android.os.Bundle
import network.loki.messenger.R
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.webrtc.*

class WebRtcTestsActivity: PassphraseRequiredActionBarActivity(), PeerConnection.Observer,
    SdpObserver {

    private val connectionFactory by lazy { PeerConnectionFactory.builder().createPeerConnectionFactory() }

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)
        setContentView(R.layout.activity_webrtc_tests)

        val server = PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()

        val peerConnection = connectionFactory.createPeerConnection(listOf(server), this) ?: return
        Log.d("Loki-RTC", "peer connecting?")
        peerConnection.connectionState()
        val stream = connectionFactory.createLocalMediaStream("stream")
        val audioSource = connectionFactory.createAudioSource(MediaConstraints())
        val audioTrack = connectionFactory.createAudioTrack("audio", audioSource)
        stream.addTrack(audioTrack)
        peerConnection.addStream(stream)
        peerConnection.createOffer(this, MediaConstraints())
    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
        Log.d("Loki-RTC", "onSignalingChange: $p0")
    }

    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
        Log.d("Loki-RTC", "onIceConnectionChange: $p0")
    }

    override fun onIceConnectionReceivingChange(p0: Boolean) {
        Log.d("Loki-RTC", "onIceConnectionReceivingChange: $p0")
    }

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
        Log.d("Loki-RTC", "onIceGatheringChange: $p0")
    }

    override fun onIceCandidate(p0: IceCandidate?) {
        Log.d("Loki-RTC", "onIceCandidate: $p0")
    }

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
        Log.d("Loki-RTC", "onIceCandidatesRemoved: $p0")
    }

    override fun onAddStream(p0: MediaStream?) {
        Log.d("Loki-RTC", "onAddStream: $p0")
    }

    override fun onRemoveStream(p0: MediaStream?) {
        Log.d("Loki-RTC", "onRemoveStream: $p0")
    }

    override fun onDataChannel(p0: DataChannel?) {
        Log.d("Loki-RTC", "onDataChannel: $p0")
    }

    override fun onRenegotiationNeeded() {
        Log.d("Loki-RTC", "onRenegotiationNeeded")
    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
        Log.d("Loki-RTC", "onAddTrack: $p0: $p1")
    }

    override fun onCreateSuccess(p0: SessionDescription) {
        Log.d("Loki-RTC", "onCreateSuccess: ${p0.description}, ${p0.type}")
    }

    override fun onSetSuccess() {
        Log.d("Loki-RTC", "onSetSuccess")
    }

    override fun onCreateFailure(p0: String?) {
        Log.d("Loki-RTC", "onCreateFailure: $p0")
    }

    override fun onSetFailure(p0: String?) {
        Log.d("Loki-RTC", "onSetFailure: $p0")
    }

}