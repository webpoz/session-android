package org.thoughtcrime.securesms.dependencies

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.session.libsession.database.CallDataProvider
import org.thoughtcrime.securesms.database.Storage
import org.thoughtcrime.securesms.webrtc.CallManager
import org.thoughtcrime.securesms.webrtc.audio.AudioManagerCompat
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager
import org.thoughtcrime.securesms.webrtc.data.SessionCallDataProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CallModule {

    @Provides
    @Singleton
    fun provideAudioManagerCompat(@ApplicationContext context: Context) = AudioManagerCompat.create(context)

    @Provides
    @Singleton
    fun provideCallManager(@ApplicationContext context: Context, storage: Storage, audioManagerCompat: AudioManagerCompat) =
            CallManager(context, audioManagerCompat)

    @Binds
    @Singleton
    abstract fun bindCallDataProvider(sessionCallDataProvider: SessionCallDataProvider): CallDataProvider

}