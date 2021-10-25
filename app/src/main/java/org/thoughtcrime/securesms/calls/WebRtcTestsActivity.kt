package org.thoughtcrime.securesms.calls

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.MenuItem
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.android.synthetic.main.activity_webrtc_tests.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import network.loki.messenger.R
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.WebRtcUtils
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Debouncer
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.webrtc.*
import java.util.*


class WebRtcTestsActivity: PassphraseRequiredActionBarActivity(), PeerConnection.Observer,
    SdpObserver, RTCStatsCollectorCallback {

    companion object {
        const val HD_VIDEO_WIDTH = 900
        const val HD_VIDEO_HEIGHT = 1600
        const val CALL_ID = "call_id_session"
        private const val LOCAL_TRACK_ID = "local_track"
        private const val LOCAL_STREAM_ID = "local_track"

        const val ACTION_ANSWER = "answer"
        const val ACTION_END = "end-call"

        const val EXTRA_SDP = "WebRtcTestsActivity_EXTRA_SDP"
        const val EXTRA_ADDRESS = "WebRtcTestsActivity_EXTRA_ADDRESS"
        const val EXTRA_CALL_ID = "WebRtcTestsActivity_EXTRA_CALL_ID"

    }

    private val eglBase by lazy { EglBase.create() }
    private val surfaceHelper by lazy { SurfaceTextureHelper.create(Thread.currentThread().name, eglBase.eglBaseContext) }
    private val audioSource by lazy { connectionFactory.createAudioSource(MediaConstraints()) }
    private val videoCapturer by lazy { createCameraCapturer(Camera2Enumerator(this)) }

    private val acceptedCallMessageHashes = mutableSetOf<Int>()

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

    private var localCandidateType: String? = null
        set(value) {
            field = value
            if (value != null) {
                // show it
                local_candidate_info.isVisible = true
                local_candidate_info.text = "local: $value"
            }
        }

    private var remoteCandidateType: String? = null
        set(value) {
            field = value
            if (value != null) {
                remote_candidate_info.isVisible = true
                remote_candidate_info.text = "remote: $value"
            }
            // update text
        }

    private lateinit var callAddress: Address
    private lateinit var callId: UUID

    private val peerConnection by lazy {
        // TODO: in a lokinet world, ice servers shouldn't be needed as .loki addresses should suffice to p2p
        val turn = PeerConnection.IceServer.builder("turn:freyr.getsession.org:5349").setUsername("webrtc").setPassword("webrtc").createIceServer()
//        val stun = PeerConnection.IceServer.builder("stun:freyr.getsession.org").createIceServer()
        val iceServers = mutableListOf(turn)
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            this.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            this.candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            this.iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA
        connectionFactory.createPeerConnection(rtcConfig, this)!!
    }

    override fun onBackPressed() {
        endCall()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            endCall()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)
        setContentView(R.layout.activity_webrtc_tests)

        //TODO: better handling of permissions
        Permissions.with(this)
            .request(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .onAllGranted {
                setupStreams()
            }
            .execute()

        local_renderer.run {
            setEnableHardwareScaler(true)
            init(eglBase.eglBaseContext, null)
        }

        remote_renderer.run {
            setEnableHardwareScaler(true)
            init(eglBase.eglBaseContext, null)
        }

        end_call_button.setOnClickListener {
            endCall()
        }

        switch_camera_button.setOnClickListener {
            videoCapturer?.switchCamera(null)
        }

        switch_audio_button.setOnClickListener {

        }

        // create either call or answer
        callId = intent.getStringExtra(EXTRA_CALL_ID).let(UUID::fromString)
        if (intent.action == ACTION_ANSWER) {
            callAddress = intent.getParcelableExtra(EXTRA_ADDRESS) ?: run { finish(); return }
            val offerSdp = intent.getStringArrayExtra(EXTRA_SDP)!![0]
            peerConnection.setRemoteDescription(this, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
            peerConnection.createAnswer(this, MediaConstraints())
        } else {
            callAddress = intent.getParcelableExtra(EXTRA_ADDRESS) ?: run { finish(); return }
            peerConnection.createOffer(this, MediaConstraints())
        }

        lifecycleScope.launchWhenCreated {
            while (this.isActive) {
                val answer = synchronized(WebRtcUtils.callCache) {
                    WebRtcUtils.callCache[callId]?.firstOrNull { it.type == SignalServiceProtos.CallMessage.Type.ANSWER }
                }
                if (answer != null) {
                    peerConnection.setRemoteDescription(
                        this@WebRtcTestsActivity,
                        SessionDescription(SessionDescription.Type.ANSWER, answer.sdps[0])
                    )
                    break
                }
                delay(2_000L)
            }
        }

        registerReceiver(object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                endCall()
            }
        }, IntentFilter(ACTION_END))

        lifecycleScope.launchWhenResumed {
            while (this.isActive) {
                delay(2_000L)
                peerConnection.getStats(this@WebRtcTestsActivity)
                synchronized(WebRtcUtils.callCache) {
                    val set = WebRtcUtils.callCache[callId] ?: mutableSetOf()
                    set.filter { it.hashCode() !in acceptedCallMessageHashes
                            && it.type == SignalServiceProtos.CallMessage.Type.ICE_CANDIDATES }.forEach { callMessage ->
                        callMessage.iceCandidates().forEach { candidate ->
                            peerConnection.addIceCandidate(candidate)
                        }
                        acceptedCallMessageHashes.add(callMessage.hashCode())
                    }
                }
            }
        }
    }

    override fun onStatsDelivered(statsReport: RTCStatsReport?) {
        statsReport?.let { report ->
            val usedConnection = report.statsMap.filter { (_,v) -> v.type == "candidate-pair" && v.members["writable"] == true }.asIterable().firstOrNull()?.value ?: return@let

            usedConnection.members["remoteCandidateId"]?.let { candidate ->
                runOnUiThread {
                    remoteCandidateType = report.statsMap[candidate]?.members?.get("candidateType") as? String
                }
            }

            usedConnection.members["localCandidateId"]?.let { candidate ->
                runOnUiThread {
                    localCandidateType = report.statsMap[candidate]?.members?.get("candidateType") as? String
                }
            }

            Log.d("Loki-RTC", "report is: $report")
        }
    }

    private fun endCall() {
        if (isFinishing) return
        val uuid = callId

        MessageSender.sendNonDurably(
            CallMessage.endCall(uuid),
            callAddress
        )
        peerConnection.close()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        endCall()
    }

    private fun setupStreams() {
        val videoSource = connectionFactory.createVideoSource(false)

        videoCapturer?.initialize(surfaceHelper, local_renderer.context, videoSource.capturerObserver) ?: run {
            finish()
            return
        }
        videoCapturer?.startCapture(HD_VIDEO_WIDTH, HD_VIDEO_HEIGHT, 10)

        val audioTrack = connectionFactory.createAudioTrack(LOCAL_TRACK_ID + "_audio", audioSource)
        val videoTrack = connectionFactory.createVideoTrack(LOCAL_TRACK_ID, videoSource)
        videoTrack.addSink(local_renderer)

        val stream = connectionFactory.createLocalMediaStream(LOCAL_STREAM_ID)
        stream.addTrack(videoTrack)
        stream.addTrack(audioTrack)

        peerConnection.addStream(stream)
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
                    candidates.map { it.sdpMid },
                    callId
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
                    CallMessage(SignalServiceProtos.CallMessage.Type.OFFER,
                        listOf(sdp.description),
                        listOf(),
                        listOf(),
                        callId
                    ),
                    callAddress
                )
            }
            SessionDescription.Type.ANSWER -> {
                peerConnection.setLocalDescription(this, sdp)
                MessageSender.sendNonDurably(
                    CallMessage(SignalServiceProtos.CallMessage.Type.ANSWER,
                        listOf(sdp.description),
                        listOf(),
                        listOf(),
                        callId
                    ),
                    callAddress
                )
            }
            null, SessionDescription.Type.PRANSWER -> TODO("do the PR answer create success handling") // MessageSender.send()
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

    private fun CallMessage.iceCandidates(): List<IceCandidate> {
        val candidateSize = sdpMids.size
        return (0 until candidateSize).map { i ->
            IceCandidate(sdpMids[i], sdpMLineIndexes[i], sdps[i])
        }
    }

}