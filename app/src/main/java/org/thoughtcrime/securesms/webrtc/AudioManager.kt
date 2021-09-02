package org.thoughtcrime.securesms.webrtc

import android.content.Context
import android.media.AudioManager

class RTCAudioManager(context: Context, deviceChangeListener: (currentDevice: AudioDevice?, availableDevices: Collection<AudioDevice>)->Unit) {

    enum class AudioDevice {
        SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, NONE
    }

    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

}