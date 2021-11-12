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
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.load.engine.DiskCacheStrategy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_webrtc.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.avatars.ProfileContactPhoto
import org.session.libsession.messaging.contacts.Contact
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.mms.GlideApp
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.service.WebRtcCallService
import org.thoughtcrime.securesms.util.AvatarPlaceholderGenerator
import org.thoughtcrime.securesms.webrtc.CallViewModel
import org.thoughtcrime.securesms.webrtc.CallViewModel.State.*
import org.webrtc.IceCandidate
import java.util.*

@AndroidEntryPoint
class WebRtcCallActivity: PassphraseRequiredActionBarActivity() {

    companion object {
        const val CALL_ID = "call_id_session"
        private const val LOCAL_TRACK_ID = "local_track"
        private const val LOCAL_STREAM_ID = "local_track"

        const val ACTION_ANSWER = "answer"
        const val ACTION_END = "end-call"

        const val BUSY_SIGNAL_DELAY_FINISH = 5500L
    }

    private val viewModel by viewModels<CallViewModel>()

    private val candidates: MutableList<IceCandidate> = mutableListOf()
    private val glide by lazy { GlideApp.with(this) }

    private lateinit var callAddress: Address
    private lateinit var callId: UUID

    private var uiJob: Job? = null

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_webrtc)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        initializeResources()

        Permissions.with(this)
            .request(Manifest.permission.RECORD_AUDIO)
            .onAllGranted {
                setupStreams()
            }
            .execute()

        if (intent.action == ACTION_ANSWER) {
            // answer via ViewModel
            val answerIntent = WebRtcCallService.acceptCallIntent(this)
            startService(answerIntent)
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

    private fun initializeResources() {

    }

    private fun setupStreams() {

    }

    override fun onStart() {
        super.onStart()

        uiJob = lifecycleScope.launch {

            launch {
                viewModel.callState.collect { state ->
                    remote_loading_view.isVisible = state != CALL_CONNECTED
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
                viewModel.localVideoEnabledState.collect { isEnabled ->
                    local_renderer.removeAllViews()
                    if (isEnabled) {
                        viewModel.localRenderer?.let { surfaceView ->
                            surfaceView.setZOrderOnTop(true)
                            local_renderer.addView(surfaceView)
                        }
                    }
                    local_renderer.isVisible = isEnabled
                    enableCameraButton.setImageResource(
                            if (isEnabled) R.drawable.ic_baseline_videocam_off_24
                            else R.drawable.ic_baseline_videocam_24
                    )
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

    fun getUserDisplayName(publicKey: String): String {
        val contact = DatabaseComponent.get(this).sessionContactDatabase().getContactWithSessionID(publicKey)
        return contact?.displayName(Contact.ContactContext.REGULAR) ?: publicKey
    }

    override fun onStop() {
        super.onStop()
        uiJob?.cancel()
    }
}