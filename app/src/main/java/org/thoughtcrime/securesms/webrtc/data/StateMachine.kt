package org.thoughtcrime.securesms.webrtc.data

import org.thoughtcrime.securesms.webrtc.data.State.Companion.CAN_HANGUP_STATES

sealed class State {
    object Idle : State()
    object RemotePreOffer : State()
    object RemoteRing : State()
    object LocalPreOffer : State()
    object LocalRing : State()
    object Connecting : State()
    object Connected : State()
    object Reconnecting : State()
    object Disconnected : State()
    companion object {
        val CAN_DECLINE_STATES = arrayOf(RemotePreOffer, RemoteRing)
        val PENDING_CONNECTION_STATES = arrayOf(
            LocalPreOffer,
            LocalRing,
            RemotePreOffer,
            RemoteRing,
            Connecting,
        )
        val OUTGOING_STATES = arrayOf(
            LocalPreOffer,
            LocalRing,
        )
        val CAN_HANGUP_STATES =
            arrayOf(LocalPreOffer, LocalRing, Connecting, Connected, Reconnecting)
        val CAN_RECEIVE_ICE_STATES =
            arrayOf(RemoteRing, LocalRing, Connecting, Connected, Reconnecting)
    }
}

sealed class Event(vararg val expectedStates: State, val outputState: State) {
    object ReceivePreOffer :
        Event(State.Idle, State.RemotePreOffer, outputState = State.RemotePreOffer)

    object ReceiveOffer : Event(State.RemotePreOffer, outputState = State.RemoteRing)
    object SendPreOffer : Event(State.Idle, outputState = State.LocalPreOffer)
    object SendOffer : Event(State.LocalPreOffer, outputState = State.LocalRing)
    object SendAnswer : Event(State.RemoteRing, outputState = State.Connecting)
    object ReceiveAnswer : Event(State.LocalRing, outputState = State.Connecting)
    object Connect : Event(State.Connecting, outputState = State.Connected)
    object IceFailed : Event(State.Connecting, outputState = State.Disconnected)
    object IceDisconnect : Event(State.Connected, outputState = State.Reconnecting)
    object NetworkReconnect : Event(State.Reconnecting, outputState = State.Connecting)
    object TimeOut :
        Event(State.Connecting, State.LocalRing, State.RemoteRing, outputState = State.Disconnected)

    object Hangup : Event(*CAN_HANGUP_STATES, outputState = State.Disconnected)
    object Cleanup : Event(State.Disconnected, outputState = State.Idle)
}

open class StateProcessor(initialState: State) {
    private var _currentState: State = initialState
    val currentState get() = _currentState

    open fun processEvent(event: Event, sideEffect: () -> Unit = {}): Boolean {
        if (currentState in event.expectedStates) {
            _currentState = event.outputState
            sideEffect()
            return true
        }
        return false
    }
}