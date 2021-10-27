package org.thoughtcrime.securesms.webrtc

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import org.thoughtcrime.securesms.webrtc.audio.IncomingRinger

class RTCAudioManager(context: Context, deviceChangeListener: (currentDevice: AudioDevice?, availableDevices: Collection<AudioDevice>)->Unit) {

    enum class AudioDevice {
        SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, NONE
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val incomingRinger = IncomingRinger(context)

    private val stateChannel = Channel<AudioEvent>()

    interface EventListener {
        fun onAudioDeviceChanged(activeDevice: AudioDevice, devices: Set<AudioDevice>)
    }

}