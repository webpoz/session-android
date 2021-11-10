package org.thoughtcrime.securesms.webrtc

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.session.libsignal.utilities.SettableFuture
import org.thoughtcrime.securesms.webrtc.video.Camera
import org.thoughtcrime.securesms.webrtc.video.CameraEventListener
import org.thoughtcrime.securesms.webrtc.video.CameraState
import org.webrtc.*
import java.util.concurrent.ExecutionException

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

    val readyForIce
    get() = peerConnection.localDescription != null && peerConnection.remoteDescription != null

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

    fun getCameraState(): CameraState {
        return CameraState(camera.activeDirection, camera.cameraCount)
    }

    fun createDataChannel(channelName: String): DataChannel {
        val dataChannelConfiguration = DataChannel.Init().apply {
            ordered = true
            negotiated = true
            id = 548
        }
        return peerConnection.createDataChannel(channelName, dataChannelConfiguration)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        // TODO: filter logic based on known servers
        peerConnection.addIceCandidate(candidate)
    }

    fun dispose() {
        camera.dispose()

        videoSource?.dispose()

        audioSource.dispose()
        peerConnection.close()
        peerConnection.dispose()
    }

    fun setRemoteDescription(description: SessionDescription) {
        val future = SettableFuture<Boolean>()

        peerConnection.setRemoteDescription(object: SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
                throw AssertionError()
            }

            override fun onCreateFailure(p0: String?) {
                throw AssertionError()
            }

            override fun onSetSuccess() {
                future.set(true)
            }

            override fun onSetFailure(error: String?) {
                future.setException(PeerConnectionException(error))
            }
        }, description)

        try {
            future.get()
        } catch (e: InterruptedException) {
            throw AssertionError(e)
        } catch (e: ExecutionException) {
            throw PeerConnectionException(e)
        }
    }

    fun createAnswer(mediaConstraints: MediaConstraints) : SessionDescription {
        val future = SettableFuture<SessionDescription>()

        peerConnection.createAnswer(object:SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                future.set(sdp)
            }

            override fun onSetSuccess() {
                throw AssertionError()
            }

            override fun onCreateFailure(p0: String?) {
                future.setException(PeerConnectionException(p0))
            }

            override fun onSetFailure(p0: String?) {
                throw AssertionError()
            }
        }, mediaConstraints)

        try {
            return future.get()
        } catch (e: InterruptedException) {
            throw AssertionError()
        } catch (e: ExecutionException) {
            throw PeerConnectionException(e)
        }
    }

    fun createOffer(mediaConstraints: MediaConstraints): SessionDescription {
        val future = SettableFuture<SessionDescription>()

        peerConnection.createOffer(object:SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                future.set(sdp)
            }

            override fun onSetSuccess() {
                throw AssertionError()
            }

            override fun onCreateFailure(p0: String?) {
                future.setException(PeerConnectionException(p0))
            }

            override fun onSetFailure(p0: String?) {
                throw AssertionError()
            }
        }, mediaConstraints)

        try {
            return future.get()
        } catch (e: InterruptedException) {
            throw AssertionError()
        } catch (e: ExecutionException) {
            throw PeerConnectionException(e)
        }
    }

    fun setLocalDescription(sdp: SessionDescription) {
        val future = SettableFuture<Boolean>()

        peerConnection.setLocalDescription(object: SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {

            }

            override fun onSetSuccess() {
                future.set(true)
            }

            override fun onCreateFailure(p0: String?) {}

            override fun onSetFailure(error: String?) {
                future.setException(PeerConnectionException(error))
            }
        }, sdp)

        try {
            future.get()
        } catch(e: InterruptedException) {
            throw AssertionError(e)
        } catch(e: ExecutionException) {
            throw PeerConnectionException(e)
        }
    }

    fun setCommunicationMode() {
        peerConnection.setAudioPlayout(true)
        peerConnection.setAudioRecording(true)
    }

    fun setAudioEnabled(isEnabled: Boolean) {
        audioTrack.setEnabled(isEnabled)
    }

    fun setVideoEnabled(isEnabled: Boolean) {
        videoTrack?.let { track ->
            track.setEnabled(isEnabled)
            camera.enabled = isEnabled
        }
    }

    fun flipCamera() {
        camera.flip()
    }

}