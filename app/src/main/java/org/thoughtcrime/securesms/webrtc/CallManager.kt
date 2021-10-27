package org.thoughtcrime.securesms.webrtc

import android.content.Context
import com.android.mms.transaction.MessageSender
import org.thoughtcrime.securesms.database.Storage
import java.util.concurrent.Executors
import javax.inject.Inject

class CallManager @Inject constructor(
        private val context: Context,
        private val storage: Storage,
        ) {

    private val serviceExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()

}