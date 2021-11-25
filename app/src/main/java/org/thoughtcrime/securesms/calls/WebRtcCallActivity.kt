package org.thoughtcrime.securesms.calls

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.load.engine.DiskCacheStrategy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_conversation_v2.*
import kotlinx.android.synthetic.main.activity_webrtc.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.avatars.ProfileContactPhoto
import org.session.libsession.messaging.contacts.Contact
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.mms.GlideApp
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.service.WebRtcCallService
import org.thoughtcrime.securesms.util.AvatarPlaceholderGenerator
import org.thoughtcrime.securesms.webrtc.AudioManagerCommand
import org.thoughtcrime.securesms.webrtc.CallViewModel
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.*
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager.AudioDevice.*
import java.util.*

@AndroidEntryPoint
class WebRtcCallActivity: PassphraseRequiredActionBarActivity() {

    companion object {
        const val ACTION_PRE_OFFER = "pre-offer"
        const val ACTION_ANSWER = "answer"
        const val ACTION_END = "end-call"

        const val BUSY_SIGNAL_DELAY_FINISH = 5500L
    }

    private val viewModel by viewModels<CallViewModel>()
    private val glide by lazy { GlideApp.with(this) }
    private var uiJob: Job? = null
    private var wantsToAnswer = false

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == ACTION_ANSWER) {
            val answerIntent = WebRtcCallService.acceptCallIntent(this)
            ContextCompat.startForegroundService(this,answerIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_webrtc)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        if (intent.action == ACTION_ANSWER) {
            answerCall()
        }

        if (intent.action == ACTION_PRE_OFFER) {
            wantsToAnswer = true
        }

        microphoneButton.setOnClickListener {
            val audioEnabledIntent = WebRtcCallService.microphoneIntent(this, !viewModel.microphoneEnabled)
            startService(audioEnabledIntent)
        }

        speakerPhoneButton.setOnClickListener {
            val command = AudioManagerCommand.SetUserDevice( if (viewModel.isSpeaker) EARPIECE else SPEAKER_PHONE)
            WebRtcCallService.sendAudioManagerCommand(this, command)
        }

        acceptCallButton.setOnClickListener {
            val answerIntent = WebRtcCallService.acceptCallIntent(this)
            ContextCompat.startForegroundService(this,answerIntent)
        }

        declineCallButton.setOnClickListener {
            val declineIntent = WebRtcCallService.denyCallIntent(this)
            startService(declineIntent)
        }

        registerReceiver(object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                finish()
            }
        },IntentFilter(ACTION_END))

        enableCameraButton.setOnClickListener {
            Permissions.with(this)
                    .request(Manifest.permission.CAMERA)
                    .onAllGranted {
                        val intent = WebRtcCallService.cameraEnabled(this, !viewModel.videoEnabled)
                        startService(intent)
                    }
                    .execute()
        }

        switchCameraButton.setOnClickListener {
            startService(WebRtcCallService.flipCamera(this))
        }

        endCallButton.setOnClickListener {
            startService(WebRtcCallService.hangupIntent(this))
        }

    }

    private fun answerCall() {
        val answerIntent = WebRtcCallService.acceptCallIntent(this)
        ContextCompat.startForegroundService(this,answerIntent)
    }

    override fun onStart() {
        super.onStart()

        uiJob = lifecycleScope.launch {

            launch {
                viewModel.audioDeviceState.collect { state ->
                    val speakerEnabled = state.selectedDevice == SPEAKER_PHONE
                    // change drawable background to enabled or not
                    speakerPhoneButton.isSelected = speakerEnabled
                }
            }

            launch {
                viewModel.callState.collect { state ->
                    when (state) {
                        CALL_RINGING -> {
                            if (wantsToAnswer) {
                                answerCall()
                            }
                        }
                        CALL_OUTGOING -> {
                        }
                        CALL_CONNECTED -> {
                        }
                    }
                    controlGroup.isVisible = state in listOf(CALL_CONNECTED, CALL_OUTGOING, CALL_INCOMING)
                    remote_loading_view.isVisible = state !in listOf(CALL_CONNECTED, CALL_RINGING)
                    incomingControlGroup.isVisible = state == CALL_RINGING
                }
            }

            launch {
                viewModel.recipient.collect { latestRecipient ->
                    if (latestRecipient.recipient != null) {
                        val signalProfilePicture = latestRecipient.recipient.contactPhoto
                        val avatar = (signalProfilePicture as? ProfileContactPhoto)?.avatarObject
                        if (signalProfilePicture != null && avatar != "0" && avatar != "") {
                            glide.clear(remote_recipient)
                            glide.load(signalProfilePicture).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).circleCrop().into(remote_recipient)
                        } else {
                            val publicKey = latestRecipient.recipient.address.serialize()
                            val displayName = getUserDisplayName(publicKey)
                            val sizeInPX = resources.getDimensionPixelSize(R.dimen.extra_large_profile_picture_size)
                            glide.clear(remote_recipient)
                            glide.load(AvatarPlaceholderGenerator.generate(this@WebRtcCallActivity, sizeInPX, publicKey, displayName))
                                    .diskCacheStrategy(DiskCacheStrategy.ALL).circleCrop().into(remote_recipient)
                        }
                    } else {
                        glide.clear(remote_recipient)
                    }
                }
            }

            launch {
                viewModel.localAudioEnabledState.collect { isEnabled ->
                    // change drawable background to enabled or not
                    microphoneButton.isSelected = !isEnabled
                }
            }

            launch {
                viewModel.localVideoEnabledState.collect { isEnabled ->
                    local_renderer.removeAllViews()
                    if (isEnabled) {
                        viewModel.localRenderer?.let { surfaceView ->
                            surfaceView.setZOrderOnTop(true)
                            local_renderer.addView(surfaceView)
                        }
                    }
                    local_renderer.isVisible = isEnabled
                    enableCameraButton.isSelected = isEnabled
                }
            }

            launch {
                viewModel.remoteVideoEnabledState.collect { isEnabled ->
                    remote_renderer.removeAllViews()
                    if (isEnabled) {
                        viewModel.remoteRenderer?.let { remote_renderer.addView(it) }
                    }
                    remote_renderer.isVisible = isEnabled
                    remote_recipient.isVisible = !isEnabled
                }
            }
        }
    }

    private fun getUserDisplayName(publicKey: String): String {
        val contact = DatabaseComponent.get(this).sessionContactDatabase().getContactWithSessionID(publicKey)
        return contact?.displayName(Contact.ContactContext.REGULAR) ?: publicKey
    }

    override fun onStop() {
        super.onStop()
        uiJob?.cancel()
    }
}