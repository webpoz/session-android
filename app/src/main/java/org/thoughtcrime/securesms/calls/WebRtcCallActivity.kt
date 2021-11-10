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
import com.jakewharton.rxbinding3.view.clicks
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_webrtc_tests.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.service.WebRtcCallService
import org.thoughtcrime.securesms.webrtc.CallViewModel
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
        setContentView(R.layout.activity_webrtc_tests)
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
            startService(WebRtcCallService.cameraEnabled(this, true))
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

            viewModel.callState.collect { state ->
                if (state == CallViewModel.State.CALL_CONNECTED) {
                    // call connected, render the surfaces
                    remote_renderer.removeAllViews()
                    local_renderer.removeAllViews()
                    viewModel.remoteRenderer?.let { remote_renderer.addView(it) }
                    viewModel.localRenderer?.let { local_renderer.addView(it) }
                }
            }

            viewModel.remoteVideoEnabledState.collect {

            }
        }
    }

    override fun onStop() {
        super.onStop()
        uiJob?.cancel()
    }
}