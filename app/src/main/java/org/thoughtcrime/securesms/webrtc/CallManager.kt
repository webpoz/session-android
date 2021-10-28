package org.thoughtcrime.securesms.webrtc

import android.content.Context
import com.android.mms.transaction.MessageSender
import org.thoughtcrime.securesms.database.Storage
import java.util.concurrent.Executors
import javax.inject.Inject

class CallManager(private val context: Context,
                   private val storage: Storage) {

    private val serviceExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()



    fun networkChange(networkAvailable: Boolean) {

    }

    fun acceptCall() {

    }

    fun declineCall() {

    }

    fun setAudioEnabled(isEnabled: Boolean) {

    }

    fun setVideoEnabled(isEnabled: Boolean) {

    }

}