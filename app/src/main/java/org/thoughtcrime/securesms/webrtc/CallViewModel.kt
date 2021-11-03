package org.thoughtcrime.securesms.webrtc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.session.libsession.messaging.messages.control.CallMessage
import org.webrtc.*
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(private val callManager: CallManager): ViewModel() {

    val localAudioEnabledState = callManager.audioEvents.map { it.isEnabled }
    val localVideoEnabledState = callManager.videoEvents.map { it.isEnabled }
    val remoteVideoEnabledState = callManager.remoteVideoEvents.map { it.isEnabled }
    // set up listeners for establishing connection toggling video / audio
    init {
        callManager.audioEvents.onEach { (enabled) -> callManager.setAudioEnabled(enabled) }
                .launchIn(viewModelScope)
        callManager.videoEvents.onEach { (enabled) -> callManager.setVideoEnabled(enabled) }
                .launchIn(viewModelScope)
    }

}