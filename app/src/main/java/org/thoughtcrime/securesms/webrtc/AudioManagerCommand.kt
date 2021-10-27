package org.thoughtcrime.securesms.webrtc

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager

@Parcelize
open class AudioManagerCommand: Parcelable {
    @Parcelize
    object Initialize: AudioManagerCommand()

    @Parcelize
    object StartOutgoingRinger: AudioManagerCommand()

    @Parcelize
    object SilenceIncomingRinger: AudioManagerCommand()

    @Parcelize
    object Start: AudioManagerCommand()

    @Parcelize
    data class Stop(val playDisconnect: Boolean): AudioManagerCommand()

    @Parcelize
    data class StartIncomingRinger(val vibrate: Boolean): AudioManagerCommand()

    @Parcelize
    data class SetUserDevice(val device: SignalAudioManager.AudioDevice): AudioManagerCommand()

    @Parcelize
    data class SetDefaultDevice(val device: SignalAudioManager.AudioDevice,
                                val clearUserEarpieceSelection: Boolean): AudioManagerCommand()
}