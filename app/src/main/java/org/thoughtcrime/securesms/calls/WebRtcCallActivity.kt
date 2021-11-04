package org.thoughtcrime.securesms.calls

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.view.MenuItem
import android.view.Window
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_webrtc_tests.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.WebRtcUtils
import org.session.libsession.utilities.Address
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.webrtc.CallViewModel
import org.webrtc.*
import java.util.*

@AndroidEntryPoint
class WebRtcCallActivity: PassphraseRequiredActionBarActivity() {

    companion object {
        const val CALL_ID = "call_id_session"
        private const val LOCAL_TRACK_ID = "local_track"
        private const val LOCAL_STREAM_ID = "local_track"

        const val ACTION_ANSWER = "answer"
        const val ACTION_END = "end-call"

        const val EXTRA_SDP = "WebRtcTestsActivity_EXTRA_SDP"
        const val EXTRA_ADDRESS = "WebRtcTestsActivity_EXTRA_ADDRESS"
        const val EXTRA_CALL_ID = "WebRtcTestsActivity_EXTRA_CALL_ID"

        const val BUSY_SIGNAL_DELAY_FINISH = 5500L
    }

    private val viewModel by viewModels<CallViewModel>()

    private val acceptedCallMessageHashes = mutableSetOf<Int>()

    private val candidates: MutableList<IceCandidate> = mutableListOf()

    private lateinit var callAddress: Address
    private lateinit var callId: UUID

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
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_webrtc_tests)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        initializeResources()

        Permissions.with(this)
            .request(Manifest.permission.RECORD_AUDIO)
            .onAllGranted {
                setupStreams()
            }
            .execute()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel
            }
        }

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
                        this@WebRtcCallActivity,
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
                peerConnection.getStats(this@WebRtcCallActivity)
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

    private fun initializeResources() {

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

}