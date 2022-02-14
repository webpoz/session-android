package org.thoughtcrime.securesms.webrtc.video

import org.session.libsignal.utilities.Log
import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean

class RotationVideoSink: CapturerObserver {

    var rotation: Int = 0

    private val capturing = AtomicBoolean(false)
    private var capturerObserver = SoftReference<CapturerObserver>(null)

    override fun onCapturerStarted(ignored: Boolean) {
        capturing.set(true)
    }

    override fun onCapturerStopped() {
        capturing.set(false)
    }

    override fun onFrameCaptured(videoFrame: VideoFrame?) {
        // rotate if need
        val observer = capturerObserver.get()
        if (videoFrame == null || observer == null || !capturing.get()) return

        val quadrantRotation = when (rotation % 360) {
            in 0 until 90 -> 90
            in 90 until 180 -> 180
            in 180 until 270 -> 270
            else -> 0
        }

        val newFrame = VideoFrame(videoFrame.buffer, quadrantRotation, videoFrame.timestampNs)
        observer.onFrameCaptured(newFrame)
    }

    fun setObserver(videoSink: CapturerObserver?) {
        capturerObserver = SoftReference(videoSink)
    }
}