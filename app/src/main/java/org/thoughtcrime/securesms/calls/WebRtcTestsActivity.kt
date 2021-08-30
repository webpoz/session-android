package org.thoughtcrime.securesms.calls

import android.content.Intent
import android.os.Bundle
import kotlinx.android.synthetic.main.activity_webrtc_tests.*
import network.loki.messenger.R
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Debouncer
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.webrtc.*


class WebRtcTestsActivity: PassphraseRequiredActionBarActivity(), PeerConnection.Observer,
    SdpObserver {

    companion object {
        const val HD_VIDEO_WIDTH = 320
        const val HD_VIDEO_HEIGHT = 240
        const val CALL_ID = "call_id_session"
        private const val LOCAL_TRACK_ID = "local_track"
        private const val LOCAL_STREAM_ID = "local_track"

        const val ACTION_ANSWER = "answer"
        const val ACTION_UPDATE_ICE = "updateIce"

        const val EXTRA_SDP = "WebRtcTestsActivity_EXTRA_SDP"
        const val EXTRA_ADDRESS = "WebRtcTestsActivity_EXTRA_ADDRESS"
        const val EXTRA_SDP_MLINE_INDEXES = "WebRtcTestsActivity_EXTRA_SDP_MLINE_INDEXES"
        const val EXTRA_SDP_MIDS = "WebRtcTestsActivity_EXTRA_SDP_MIDS"

    }

    private val eglBase by lazy { EglBase.create() }
    private val surfaceHelper by lazy { SurfaceTextureHelper.create(Thread.currentThread().name, eglBase.eglBaseContext) }

    private val connectionFactory by lazy {

        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)

        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(decoderFactory)
            .setVideoEncoderFactory(encoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    private val candidates: MutableList<IceCandidate> = mutableListOf()
    private val iceDebouncer = Debouncer(2_000)

    private lateinit var callAddress: Address
    private val peerConnection by lazy {
        // TODO: in a lokinet world, ice servers shouldn't be needed as .loki addresses should suffice to p2p
        val server = PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        val server1 = PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        val server2 = PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        val server3 = PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer()
        val server4 = PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
        val rtcConfig = PeerConnection.RTCConfiguration(listOf(server, server1, server2, server3, server4))
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA
        connectionFactory.createPeerConnection(rtcConfig, this)!!
    }

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)
        setContentView(R.layout.activity_webrtc_tests)

        local_renderer.run {
            setMirror(true)
            setEnableHardwareScaler(true)
            init(eglBase.eglBaseContext, null)
        }
        remote_renderer.run {
            setMirror(true)
            setEnableHardwareScaler(true)
            init(eglBase.eglBaseContext, null)
        }

        val audioSource = connectionFactory.createAudioSource(MediaConstraints())
        val videoSource = connectionFactory.createVideoSource(false)

        val videoCapturer = createCameraCapturer(Camera2Enumerator(this)) ?: kotlin.run { finish(); return }
        videoCapturer.initialize(surfaceHelper, local_renderer.context, videoSource.capturerObserver)
        videoCapturer.startCapture(HD_VIDEO_WIDTH, HD_VIDEO_HEIGHT, 10)

        val audioTrack = connectionFactory.createAudioTrack(LOCAL_TRACK_ID + "_audio", audioSource)
        val videoTrack = connectionFactory.createVideoTrack(LOCAL_TRACK_ID, videoSource)
        videoTrack.addSink(local_renderer)

        val stream = connectionFactory.createLocalMediaStream(LOCAL_STREAM_ID)
        stream.addTrack(videoTrack)
        stream.addTrack(audioTrack)

        peerConnection.addStream(stream)

        // create either call or answer
        if (intent.action == ACTION_ANSWER) {
            callAddress = intent.getParcelableExtra(EXTRA_ADDRESS) ?: run { finish(); return }
            val offerSdp = intent.getStringArrayExtra(EXTRA_SDP)!![0]
            peerConnection.setRemoteDescription(this, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
            peerConnection.createAnswer(this, MediaConstraints())
        } else {
            callAddress = intent.getParcelableExtra(EXTRA_ADDRESS) ?: run { finish(); return }
            peerConnection.createOffer(this, MediaConstraints())
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        callAddress = intent.getParcelableExtra(EXTRA_ADDRESS) ?: run { finish(); return }
        when (intent.action) {
            ACTION_ANSWER -> {
                peerConnection.setRemoteDescription(this,
                    SessionDescription(SessionDescription.Type.ANSWER, intent.getStringArrayExtra(EXTRA_SDP)!![0])
                )
            }
            ACTION_UPDATE_ICE -> {
                val sdpIndexes = intent.getIntArrayExtra(EXTRA_SDP_MLINE_INDEXES)!!
                val sdpMids = intent.getStringArrayExtra(EXTRA_SDP_MIDS)!!
                val sdp = intent.getStringArrayExtra(EXTRA_SDP)!!
                val amount = minOf(sdpIndexes.size, sdpMids.size)
                (0 until amount).map { index ->
                    val candidate = IceCandidate(sdpMids[index], sdpIndexes[index], sdp[index])
                    peerConnection.addIceCandidate(candidate)
                }
            }
        }
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames

        // First, try to find front facing camera
        Log.d("Loki-RTC-vid", "Looking for front facing cameras.")
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Log.d("Loki-RTC-vid", "Creating front facing camera capturer.")
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
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
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        return null
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
        p0 ?: return
        Log.d("Loki-RTC","sending IceCandidates of size: ${candidates.size}")
        MessageSender.sendNonDurably(
            CallMessage(SignalServiceProtos.CallMessage.Type.ICE_CANDIDATES,
                candidates.map { it.sdp },
                candidates.map { it.sdpMLineIndex },
                candidates.map { it.sdpMid }
            ),
            callAddress
        )
    }

    override fun onIceCandidate(iceCandidate: IceCandidate?) {
        Log.d("Loki-RTC", "onIceCandidate: $iceCandidate")
        if (iceCandidate == null) return
        // TODO: in a lokinet world, these might have to be filtered specifically to drop anything that is not .loki
        peerConnection.addIceCandidate(iceCandidate)
        candidates.add(iceCandidate)
        iceDebouncer.publish {
            MessageSender.sendNonDurably(
                CallMessage(SignalServiceProtos.CallMessage.Type.ICE_CANDIDATES,
                    candidates.map { it.sdp },
                    candidates.map { it.sdpMLineIndex },
                    candidates.map { it.sdpMid }
                ),
                callAddress
            )
        }
    }

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
        Log.d("Loki-RTC", "onIceCandidatesRemoved: $p0")
        peerConnection.removeIceCandidates(p0)
    }

    override fun onAddStream(remoteStream: MediaStream?) {
        Log.d("Loki-RTC", "onAddStream: $remoteStream")
        if (remoteStream == null) {
            return
        }

        remoteStream.videoTracks.firstOrNull()?.addSink(remote_renderer)
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

    override fun onCreateSuccess(sdp: SessionDescription) {
        Log.d("Loki-RTC", "onCreateSuccess: ${sdp.type}")
        when (sdp.type) {
            SessionDescription.Type.OFFER -> {
                peerConnection.setLocalDescription(this, sdp)
                MessageSender.sendNonDurably(
                    CallMessage(SignalServiceProtos.CallMessage.Type.OFFER, listOf(sdp.description), listOf(), listOf()),
                    callAddress
                )
            }
            SessionDescription.Type.ANSWER -> {
                peerConnection.setLocalDescription(this, sdp)
                MessageSender.sendNonDurably(
                    CallMessage(SignalServiceProtos.CallMessage.Type.ANSWER, listOf(sdp.description), listOf(), listOf()),
                    callAddress
                )
            }
            SessionDescription.Type.PRANSWER -> TODO("do the PR answer create success handling") // MessageSender.send()
        }
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