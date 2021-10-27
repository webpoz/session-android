package org.thoughtcrime.securesms.dependencies

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.webrtc.audio.AudioManagerCompat

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CallComponent {

    companion object {
        @JvmStatic
        fun get(context: Context) = ApplicationContext.getInstance(context).callComponent
    }

    fun callManagerCompat(): AudioManagerCompat

}