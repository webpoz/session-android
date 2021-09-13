package org.thoughtcrime.securesms.webrtc

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.android.synthetic.main.fragment_call_bottom_sheet.*
import kotlinx.android.synthetic.main.fragment_user_details_bottom_sheet.nameTextView
import kotlinx.android.synthetic.main.fragment_user_details_bottom_sheet.profilePictureView
import network.loki.messenger.R
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.calls.WebRtcTestsActivity
import org.thoughtcrime.securesms.mms.GlideApp

class CallBottomSheet: BottomSheetDialogFragment() {

    companion object {
        const val ARGUMENT_ADDRESS = "CallBottomSheet_ADDRESS"
        const val ARGUMENT_SDP = "CallBottomSheet_SDP"
        const val ARGUMENT_TYPE = "CallBottomSheet_TYPE"
    }

    private lateinit var address: Address

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_call_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        address = arguments?.getParcelable(ARGUMENT_ADDRESS) ?: return dismiss()
        val sdp = arguments?.getStringArray(ARGUMENT_SDP) ?: return dismiss()
        val recipient = Recipient.from(requireContext(), address, false)
        profilePictureView.publicKey = address.serialize()
        profilePictureView.glide = GlideApp.with(this)
        profilePictureView.isLarge = true
        profilePictureView.update(recipient, -1)

        nameTextView.text = recipient.name ?: address.serialize()

        acceptButton.setOnClickListener {
            val intent = Intent(requireContext(), WebRtcTestsActivity::class.java)
            val bundle = bundleOf(
                WebRtcTestsActivity.EXTRA_ADDRESS to address,
                WebRtcTestsActivity.EXTRA_RELAY_USED to relaySwitch.isChecked
            )
            intent.action = WebRtcTestsActivity.ACTION_ANSWER
            bundle.putStringArray(WebRtcTestsActivity.EXTRA_SDP, sdp)

            intent.putExtras(bundle)
            startActivity(intent)
            dismiss()
        }

        declineButton.setOnClickListener {
            MessageSender.sendNonDurably(CallMessage.endCall(), address)
            dismiss()
        }

    }
}