package org.thoughtcrime.securesms.webrtc.video

import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.webrtc.data.quadrantRotation
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

class RemoteRotationVideoProxySink: VideoSink {

    private var targetSink: VideoSink? = null

    var rotation: Int = 0

    override fun onFrame(frame: VideoFrame?) {
        val thisSink = targetSink ?: return
        val thisFrame = frame ?: return

        val quadrantRotation = rotation.quadrantRotation()

        val newFrame = VideoFrame(thisFrame.buffer, (thisFrame.rotation - quadrantRotation) % 360, thisFrame.timestampNs)

        thisSink.onFrame(newFrame)
    }

    fun setSink(videoSink: VideoSink) {
        targetSink = videoSink
    }

    fun release() {
        targetSink = null
    }

}