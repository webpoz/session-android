package org.thoughtcrime.securesms.webrtc

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
sealed class AudioManagerCommand: Parcelable {
    @Parcelize object StartOutgoingRinger: AudioManagerCommand()
    @Parcelize object SilenceIncomingRinger: AudioManagerCommand()
    @Parcelize object Start: AudioManagerCommand()
    @Parcelize object Stop: AudioManagerCommand()
    @Parcelize object SetUserDevice: AudioManagerCommand()
}