package org.thoughtcrime.securesms.webrtc.video

import kotlinx.coroutines.newSingleThreadContext
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink

class RotationVideoProcessor: VideoProcessor {

    private var isCapturing: Boolean = true
    private var sink: VideoSink? = null

    var rotation: Int = 0

    override fun onCapturerStarted(p0: Boolean) {
        isCapturing = true
    }

    override fun onCapturerStopped() {
        isCapturing = false
    }

    override fun onFrameCaptured(frame: VideoFrame?) {
        val thisSink = sink ?: return
        val thisFrame = frame ?: return

        val newFrame = VideoFrame(thisFrame.buffer, rotation, thisFrame.timestampNs)

        thisSink.onFrame(newFrame)
    }

    override fun setSink(newSink: VideoSink?) {
        sink = newSink
    }
}