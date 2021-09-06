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

    override fun isValid(): Boolean = super.isValid() && type != null
            && (!sdps.isNullOrEmpty() || type == SignalServiceProtos.CallMessage.Type.END_CALL)

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

        fun endCall() = CallMessage(SignalServiceProtos.CallMessage.Type.END_CALL, emptyList(), emptyList(), emptyList())

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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CallMessage

        if (type != other.type) return false
        if (sdps != other.sdps) return false
        if (sdpMLineIndexes != other.sdpMLineIndexes) return false
        if (sdpMids != other.sdpMids) return false
        if (isSelfSendValid != other.isSelfSendValid) return false
        if (ttl != other.ttl) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type?.hashCode() ?: 0
        result = 31 * result + sdps.hashCode()
        result = 31 * result + sdpMLineIndexes.hashCode()
        result = 31 * result + sdpMids.hashCode()
        result = 31 * result + isSelfSendValid.hashCode()
        result = 31 * result + ttl.hashCode()
        return result
    }


}