package org.thoughtcrime.securesms.util

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import network.loki.messenger.R
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.calls.WebRtcCallActivity
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.service.WebRtcCallService

class CallNotificationBuilder {

    companion object {
        const val WEBRTC_NOTIFICATION = 313388

        const val TYPE_INCOMING_RINGING = 1
        const val TYPE_OUTGOING_RINGING = 2
        const val TYPE_ESTABLISHED = 3
        const val TYPE_INCOMING_CONNECTING = 4

        @JvmStatic
        fun getCallInProgressNotification(context: Context, type: Int, recipient: Recipient?): Notification {
            val contentIntent = Intent(context, WebRtcCallActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

            val pendingIntent = PendingIntent.getActivity(context, 0, contentIntent, 0)

            val builder = NotificationCompat.Builder(context, NotificationChannels.CALLS)
                    .setSmallIcon(R.drawable.ic_baseline_call_24)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)

            recipient?.name?.let { name ->
                builder.setContentTitle(name)
            }

            when (type) {
                TYPE_INCOMING_CONNECTING -> {
                    builder.setContentText(context.getString(R.string.CallNotificationBuilder_connecting))
                    builder.priority = NotificationCompat.PRIORITY_MIN
                }
                TYPE_INCOMING_RINGING -> {
                    builder.setContentText(context.getString(R.string.NotificationBarManager__incoming_signal_call))
                    builder.addAction(getServiceNotificationAction(
                            context,
                            WebRtcCallService.ACTION_DENY_CALL,
                            R.drawable.ic_close_grey600_32dp,
                            R.string.NotificationBarManager__deny_call
                    ))
                    builder.addAction(getActivityNotificationAction(
                            context,
                            WebRtcCallActivity.ACTION_ANSWER,
                            R.drawable.ic_phone_grey600_32dp,
                            R.string.NotificationBarManager__answer_call
                    ))
                    builder.priority = NotificationCompat.PRIORITY_HIGH
                }
                TYPE_OUTGOING_RINGING -> {
                    builder.setContentText(context.getString(R.string.NotificationBarManager__establishing_signal_call))
                    builder.addAction(getServiceNotificationAction(
                            context,
                            WebRtcCallService.ACTION_LOCAL_HANGUP,
                            R.drawable.ic_call_end_grey600_32dp,
                            R.string.NotificationBarManager__cancel_call
                    ))
                }
                else -> {
                    builder.setContentText(context.getString(R.string.NotificationBarManager_call_in_progress))
                    builder.addAction(getServiceNotificationAction(
                            context,
                            WebRtcCallService.ACTION_LOCAL_HANGUP,
                            R.drawable.ic_call_end_grey600_32dp,
                            R.string.NotificationBarManager__end_call
                    ))
                }
            }

            return builder.build()
        }

        @JvmStatic
        private fun getServiceNotificationAction(context: Context, action: String, iconResId: Int, titleResId: Int): NotificationCompat.Action {
            val intent = Intent(context, WebRtcCallService::class.java)
                    .setAction(action)

            val pendingIntent = PendingIntent.getService(context, 0, intent, 0)

            return NotificationCompat.Action(iconResId, context.getString(titleResId), pendingIntent)
        }

        @JvmStatic
        private fun getActivityNotificationAction(context: Context, action: String,
                                                  @DrawableRes iconResId: Int, @StringRes titleResId: Int): NotificationCompat.Action {
            val intent = Intent(context, WebRtcCallActivity::class.java)
                    .setAction(action)

            val pendingIntent = PendingIntent.getActivity(context, 0, intent, 0)

            return NotificationCompat.Action(iconResId, context.getString(titleResId), pendingIntent)
        }

    }
}