package org.thoughtcrime.securesms.webrtc.data

import org.session.libsession.database.CallDataProvider
import org.session.libsession.database.StorageProtocol
import org.thoughtcrime.securesms.webrtc.CallManager
import javax.inject.Inject

class SessionCallDataProvider @Inject constructor(private val storage: StorageProtocol,
                                                  private val callManager: CallManager): CallDataProvider {

}