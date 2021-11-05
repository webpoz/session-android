package org.thoughtcrime.securesms.calls

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.view.MenuItem
import android.view.Window
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_webrtc_tests.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.WebRtcUtils
import org.session.libsession.utilities.Address
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.webrtc.CallViewModel
import org.webrtc.*
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

    private val acceptedCallMessageHashes = mutableSetOf<Int>()

    private val candidates: MutableList<IceCandidate> = mutableListOf()

    private lateinit var callAddress: Address
    private lateinit var callId: UUID

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
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_webrtc_tests)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        initializeResources()

        Permissions.with(this)
            .request(Manifest.permission.RECORD_AUDIO)
            .onAllGranted {
                setupStreams()
            }
            .execute()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel
            }
        }

        registerReceiver(object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                finish()
            }
        }, IntentFilter(ACTION_END))
    }

    private fun initializeResources() {

    }

    private fun setupStreams() {

    }

}