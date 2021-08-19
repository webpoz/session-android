package org.thoughtcrime.securesms.calls

import android.os.Bundle
import kotlinx.android.synthetic.main.activity_webrtc_tests.*
import network.loki.messenger.R
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.webrtc.*
import org.webrtc.RendererCommon.ScalingType


class WebRtcTestsActivity: PassphraseRequiredActionBarActivity(), PeerConnection.Observer,
    SdpObserver, RendererCommon.RendererEvents {

    companion object {
        const val HD_VIDEO_WIDTH = 1280
        const val HD_VIDEO_HEIGHT = 720
    }

    private class ProxyVideoSink : VideoSink {
        private var target: VideoSink? = null

        @Synchronized
        override fun onFrame(frame: VideoFrame) {
            if (target == null) {
                Log.d("Loki-RTC", "Dropping frame in proxy because target is null.")
                return
            }
            target!!.onFrame(frame)
        }

        @Synchronized
        fun setTarget(target: VideoSink?) {
            this.target = target
        }
    }

    private val connectionFactory by lazy { PeerConnectionFactory.builder().createPeerConnectionFactory() }
    private val remoteVideoSink = ProxyVideoSink()
    private val localVideoSink = ProxyVideoSink()

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)
        setContentView(R.layout.activity_webrtc_tests)

        val server = PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()

        val rtcConfig = PeerConnection.RTCConfiguration(listOf(server))
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val peerConnection = connectionFactory.createPeerConnection(rtcConfig, this) ?: return

        Log.d("Loki-RTC", "peer connecting?")
        val stream = connectionFactory.createLocalMediaStream("stream")
        val audioSource = connectionFactory.createAudioSource(MediaConstraints())
        val audioTrack = connectionFactory.createAudioTrack("audio", audioSource)
        val videoSource = connectionFactory.createVideoSource(false)
        val videoTrack = connectionFactory.createVideoTrack("video", videoSource)
        stream.addTrack(audioTrack)
        stream.addTrack(videoTrack)
        val remoteTrack = getRemoteVideoTrack(peerConnection) ?: return
        videoTrack.addSink(localVideoSink)
        remoteTrack.addSink(remoteVideoSink)
        remoteTrack.setEnabled(true)
        videoTrack.setEnabled(true)

        val eglBase = EglBase.create()
        local_renderer.init(eglBase.eglBaseContext, this)
        local_renderer.setScalingType(ScalingType.SCALE_ASPECT_FILL)
        remote_renderer.init(eglBase.eglBaseContext, this)

        val videoCapturer = createCameraCapturer(Camera2Enumerator(this)) ?: kotlin.run { finish(); return }
        val surfaceHelper = SurfaceTextureHelper.create("video-thread", eglBase.eglBaseContext)
        surfaceHelper.startListening(localVideoSink)
        videoCapturer.initialize(surfaceHelper, this, null)

        videoCapturer.startCapture(HD_VIDEO_WIDTH, HD_VIDEO_HEIGHT, 30)
        peerConnection.createOffer(this, MediaConstraints())
    }

    private fun getRemoteVideoTrack(peerConnection: PeerConnection): VideoTrack? = peerConnection.transceivers.firstOrNull { it.receiver.track() is VideoTrack } as VideoTrack?

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

    override fun onFirstFrameRendered() {
        Log.d("Loki-RTC-vid", "first frame rendered")
    }

    override fun onFrameResolutionChanged(p0: Int, p1: Int, p2: Int) {
        Log.d("Loki-RTC-vid", "frame resolution changed")
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