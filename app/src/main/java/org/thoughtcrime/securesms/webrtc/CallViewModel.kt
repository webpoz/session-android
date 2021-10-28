package org.thoughtcrime.securesms.webrtc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.webrtc.*
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
        private val callManager: CallManager
): ViewModel(), PeerConnection.Observer {

    sealed class StateEvent {
        data class AudioEnabled(val isEnabled: Boolean): StateEvent()
        data class VideoEnabled(val isEnabled: Boolean): StateEvent()
    }

    private val audioEnabledState = MutableStateFlow(StateEvent.AudioEnabled(true))
    private val videoEnabledState = MutableStateFlow(StateEvent.VideoEnabled(false))

    private val peerConnection = callManager.getPeerConnection(this)

    // set up listeners for establishing connection toggling video / audio
    init {
        audioEnabledState.onEach { (enabled) -> callManager.setAudioEnabled(enabled) }
                .launchIn(viewModelScope)
        videoEnabledState.onEach { (enabled) -> callManager.setVideoEnabled(enabled) }
                .launchIn(viewModelScope)
    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {

    }

    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {

    }

    override fun onIceConnectionReceivingChange(p0: Boolean) {

    }

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {

    }

    override fun onIceCandidate(p0: IceCandidate?) {

    }

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {

    }

    override fun onAddStream(p0: MediaStream?) {

    }

    override fun onRemoveStream(p0: MediaStream?) {

    }

    override fun onDataChannel(p0: DataChannel?) {

    }

    override fun onRenegotiationNeeded() {

    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {

    }

}