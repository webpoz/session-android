package org.thoughtcrime.securesms.webrtc

import android.content.Context
import com.android.mms.transaction.MessageSender
import org.thoughtcrime.securesms.database.Storage
import org.webrtc.*
import java.util.concurrent.Executors
import javax.inject.Inject

class CallManager(private val context: Context,
                   private val storage: Storage): PeerConnection.Observer {

    private val serviceExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private val eglBase: EglBase = EglBase.create()

    private val connectionFactory by lazy {

        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)

        PeerConnectionFactory.builder()
                .setVideoDecoderFactory(decoderFactory)
                .setVideoEncoderFactory(encoderFactory)
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()!!
    }

    private var peerConnection: PeerConnection? = null

    private fun getPeerConnection(): PeerConnection {
        val stun = PeerConnection.IceServer.builder("stun:freyr.getsession.org:5349").setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_INSECURE_NO_CHECK).createIceServer()
        val turn = PeerConnection.IceServer.builder("turn:freyr.getsession.org:5349").setUsername("webrtc").setPassword("webrtc").setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_INSECURE_NO_CHECK).createIceServer()
        val iceServers = mutableListOf(turn, stun)
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            this.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            this.candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            // this.iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA
        return connectionFactory.createPeerConnection(rtcConfig, this)!!
    }

    fun networkChange(networkAvailable: Boolean) {

    }

    fun acceptCall() {

    }

    fun declineCall() {

    }

    fun callEnded() {
        peerConnection?.close()
        peerConnection = null
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
}