package org.thoughtcrime.securesms.webrtc

import org.session.libsignal.protos.SignalServiceProtos

interface CallDataListener {
    fun newCallMessage(callMessage: SignalServiceProtos.CallMessage)
}