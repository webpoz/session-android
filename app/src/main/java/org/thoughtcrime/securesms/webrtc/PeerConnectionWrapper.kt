package org.thoughtcrime.securesms.webrtc

import android.content.Context
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.SettableFuture
import org.thoughtcrime.securesms.webrtc.video.Camera
import org.thoughtcrime.securesms.webrtc.video.CameraEventListener
import org.thoughtcrime.securesms.webrtc.video.CameraState
import org.thoughtcrime.securesms.webrtc.video.RotationVideoProcessor
import org.thoughtcrime.securesms.webrtc.video.RotationVideoSink
import org.webrtc.*
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import kotlin.random.asKotlinRandom

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
    private val rotationVideoSink = RotationVideoSink()

    val readyForIce
        get() = peerConnection.localDescription != null && peerConnection.remoteDescription != null

    init {
        val random = SecureRandom().asKotlinRandom()
        val iceServers = listOf("freyr","fenrir","frigg","angus","hereford","holstein", "brahman").shuffled(random).take(2).map { sub ->
            PeerConnection.IceServer.builder("turn:$sub.getsession.org")
                .setUsername("session202111")
                .setPassword("053c268164bc7bd7")
                .createIceServer()
        }

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
        peerConnection.setAudioPlayout(true)
        peerConnection.setAudioRecording(true)

        val mediaStream = factory.createLocalMediaStream("ARDAMS")
        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource)
        audioTrack.setEnabled(true)
        mediaStream.addTrack(audioTrack)

        camera = Camera(context, cameraEventListener)
        if (camera.capturer != null) {
            videoSource = factory.createVideoSource(false)
            videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource)
            rotationVideoSink.setObserver(videoSource.capturerObserver)
            camera.capturer.initialize(
                    SurfaceTextureHelper.create("WebRTC-SurfaceTextureHelper", eglBase.eglBaseContext),
                            context,
                            rotationVideoSink
            )
            rotationVideoSink.setSink(localRenderer)
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

    fun setNewOffer(description: SessionDescription) {
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
            return correctSessionDescription(future.get())
        } catch (e: InterruptedException) {
            throw AssertionError()
        } catch (e: ExecutionException) {
            throw PeerConnectionException(e)
        }
    }

    private fun correctSessionDescription(sessionDescription: SessionDescription): SessionDescription {
        val updatedSdp = sessionDescription.description.replace("(a=fmtp:111 ((?!cbr=).)*)\r?\n".toRegex(), "$1;cbr=1\r\n")
                .replace(".+urn:ietf:params:rtp-hdrext:ssrc-audio-level.*\r?\n".toRegex(), "")

        return SessionDescription(sessionDescription.type, updatedSdp)
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
            return correctSessionDescription(future.get())
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

    fun setDeviceRotation(rotation: Int) {
        Log.d("Loki", "rotation: $rotation")
        rotationVideoSink.rotation = rotation
    }

    fun setVideoEnabled(isEnabled: Boolean) {
        videoTrack?.let { track ->
            track.setEnabled(isEnabled)
            camera.enabled = isEnabled
        }
    }

    fun isVideoEnabled() = camera.enabled

    fun flipCamera() {
        camera.flip()
    }

}