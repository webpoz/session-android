package org.thoughtcrime.securesms.webrtc.data

import org.session.libsession.database.CallDataProvider
import org.session.libsession.database.StorageProtocol
import javax.inject.Inject

class SessionCallDataProvider @Inject constructor(private val storage: StorageProtocol): CallDataProvider {



}