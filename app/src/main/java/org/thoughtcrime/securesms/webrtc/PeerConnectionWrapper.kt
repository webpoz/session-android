package org.thoughtcrime.securesms.webrtc

import android.content.Context
import org.thoughtcrime.securesms.webrtc.video.Camera
import org.thoughtcrime.securesms.webrtc.video.CameraEventListener
import org.webrtc.*

class PeerConnectionWrapper(context: Context,
                            factory: PeerConnectionFactory,
                            observer: PeerConnection.Observer,
                            localRenderer: VideoSink,
                            cameraEventListener: CameraEventListener,
                            eglBase: EglBase,
                            relay: Boolean = false) {

    private val peerConnection: PeerConnection
    private val audioTrack: AudioTrack
    private val audioSource: AudioSource
    private val camera: Camera
    private val videoSource: VideoSource?
    private val videoTrack: VideoTrack?

    init {
        val stun = PeerConnection.IceServer.builder("stun:freyr.getsession.org:5349").createIceServer()
        val turn = PeerConnection.IceServer.builder("turn:freyr.getsession.org:5349").setUsername("webrtc").setPassword("webrtc").createIceServer()
        val iceServers = listOf(stun,turn)

        val constraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }
        val audioConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }

        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            if (relay) {
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
        }

        peerConnection = factory.createPeerConnection(configuration, constraints, observer)!!
        peerConnection.setAudioPlayout(false)
        peerConnection.setAudioRecording(false)

        val mediaStream = factory.createLocalMediaStream("ARDAMS")
        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource)
        audioTrack.setEnabled(false)
        mediaStream.addTrack(audioTrack)

        camera = Camera(context, cameraEventListener)
        if (camera.capturer != null) {
            videoSource = factory.createVideoSource(false)
            videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource)

            camera.capturer.initialize(
                    SurfaceTextureHelper.create("WebRTC-SurfaceTextureHelper", eglBase.eglBaseContext),
                            context,
                            videoSource.capturerObserver
            )

            videoTrack.addSink(localRenderer)
            videoTrack.setEnabled(false)
            mediaStream.addTrack(videoTrack)
        } else {
            videoSource = null
            videoTrack = null
        }

        peerConnection.addStream(mediaStream)
    }

}