package org.session.libsession.messaging.messages.control

import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Log

class CallMessage(): ControlMessage() {
    var type: SignalServiceProtos.CallMessage.Type? = null
    var sdps: List<String> = listOf()
    var sdpMLineIndexes: List<Int> = listOf()
    var sdpMids: List<String> = listOf()

    override val isSelfSendValid: Boolean = false

    override val ttl: Long = 5 * 60 * 1000

    override fun isValid(): Boolean = super.isValid() && type != null && !sdps.isNullOrEmpty()

    constructor(type: SignalServiceProtos.CallMessage.Type,
                sdps: List<String>,
                sdpMLineIndexes: List<Int>,
                sdpMids: List<String>) : this() {
        this.type = type
        this.sdps = sdps
        this.sdpMLineIndexes = sdpMLineIndexes
        this.sdpMids = sdpMids
    }

    companion object {
        const val TAG = "CallMessage"

        fun fromProto(proto: SignalServiceProtos.Content): CallMessage? {
            val callMessageProto = if (proto.hasCallMessage()) proto.callMessage else return null
            val type = callMessageProto.type
            val sdps = callMessageProto.sdpsList
            val sdpMLineIndexes = callMessageProto.sdpMLineIndexesList
            val sdpMids = callMessageProto.sdpMidsList
            return CallMessage(type,sdps, sdpMLineIndexes, sdpMids)
        }
    }

    override fun toProto(): SignalServiceProtos.Content? {
        val nonNullType = type ?: run {
            Log.w(TAG,"Couldn't construct call message request proto from: $this")
            return null
        }

        val callMessage = SignalServiceProtos.CallMessage.newBuilder()
            .setType(nonNullType)
            .addAllSdps(sdps)
            .addAllSdpMLineIndexes(sdpMLineIndexes)
            .addAllSdpMids(sdpMids)

        return SignalServiceProtos.Content.newBuilder()
            .setCallMessage(
                callMessage
            )
            .build()
    }
}