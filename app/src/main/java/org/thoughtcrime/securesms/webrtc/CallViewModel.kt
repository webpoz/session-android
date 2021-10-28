package org.thoughtcrime.securesms.webrtc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
        private val callManager: CallManager
): ViewModel() {

    sealed class StateEvent {
        data class AudioEnabled(val isEnabled: Boolean): StateEvent()
        data class VideoEnabled(val isEnabled: Boolean): StateEvent()
    }

    private val audioEnabledState = MutableStateFlow(StateEvent.AudioEnabled(true))
    private val videoEnabledState = MutableStateFlow(StateEvent.VideoEnabled(false))

    // set up listeners for establishing connection toggling video / audio
    init {
        audioEnabledState.onEach { (enabled) -> callManager.setAudioEnabled(enabled) }
                .launchIn(viewModelScope)
        videoEnabledState.onEach { (enabled) -> callManager.setVideoEnabled(enabled) }
                .launchIn(viewModelScope)
    }

}