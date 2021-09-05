package org.session.libsession.messaging.utilities

import kotlinx.coroutines.channels.Channel
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.utilities.Address

object WebRtcUtils {

    // TODO: move this to a better place that is persistent
    val SIGNAL_QUEUE = Channel<CallMessage>(Channel.UNLIMITED)
    val callCache: MutableMap<Address, MutableSet<CallMessage>> = mutableMapOf()

}