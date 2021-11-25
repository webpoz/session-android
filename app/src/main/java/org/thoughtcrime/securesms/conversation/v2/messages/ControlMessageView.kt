package org.thoughtcrime.securesms.conversation.v2.messages

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.view_control_message.view.*
import network.loki.messenger.R
import org.thoughtcrime.securesms.database.model.MessageRecord

class ControlMessageView : LinearLayout {

    // region Lifecycle
    constructor(context: Context) : super(context) { initialize() }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { initialize() }
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { initialize() }

    private fun initialize() {
        LayoutInflater.from(context).inflate(R.layout.view_control_message, this)
        layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
    }
    // endregion

    // region Updating
    fun bind(message: MessageRecord) {
        iconImageView.visibility = View.GONE
        val tintColor = if (message.isMissedCall) R.color.destructive else R.color.text
        iconImageView.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context,tintColor))
        if (message.isExpirationTimerUpdate) {
            iconImageView.setImageDrawable(ResourcesCompat.getDrawable(resources, R.drawable.ic_timer, context.theme))
            iconImageView.visibility = View.VISIBLE
        } else if (message.isMediaSavedNotification) {
            iconImageView.setImageDrawable(ResourcesCompat.getDrawable(resources, R.drawable.ic_file_download_white_36dp, context.theme))
            iconImageView.visibility = View.VISIBLE
        } else if (message.isCallLog) {
            val drawable = when {
                message.isIncomingCall -> R.drawable.ic_baseline_call_received_24
                message.isOutgoingCall -> R.drawable.ic_baseline_call_made_24
                else -> R.drawable.ic_baseline_call_missed_24
            }
            iconImageView.setImageDrawable(ResourcesCompat.getDrawable(resources, drawable, context.theme))
            iconImageView.visibility = View.VISIBLE
        }
        textView.text = message.getDisplayBody(context)
    }

    fun recycle() {

    }
    // endregion
}