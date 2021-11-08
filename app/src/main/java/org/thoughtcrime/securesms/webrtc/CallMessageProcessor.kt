package org.thoughtcrime.securesms.webrtc

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.utilities.WebRtcUtils
import org.session.libsession.utilities.Address
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.protos.SignalServiceProtos.CallMessage.Type.OFFER
import org.thoughtcrime.securesms.service.WebRtcCallService
import javax.inject.Inject


class CallMessageProcessor(private val context: Context, lifecycle: Lifecycle) {

    init {
        lifecycle.coroutineScope.launch {
            while (isActive) {
                val nextMessage = WebRtcUtils.SIGNAL_QUEUE.receive()
                when {
                    // TODO: handle messages as they come in
                    nextMessage.type == OFFER -> incomingCall(nextMessage)
                }
            }
        }
    }

    private fun incomingCall(callMessage: CallMessage) {
        val recipientAddress = callMessage.recipient ?: return
        val callId = callMessage.callId ?: return
        val sdp = callMessage.sdps.firstOrNull() ?: return
        val incomingIntent = WebRtcCallService.incomingCall(
                context = context,
                address = Address.fromSerialized(recipientAddress),
                sdp = sdp,
                callId = callId
        )
        context.startService(incomingIntent)
    }

}