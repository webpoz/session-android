package org.thoughtcrime.securesms.webrtc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.android.synthetic.main.fragment_user_details_bottom_sheet.*
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.mms.GlideApp

class CallBottomSheet: BottomSheetDialogFragment() {

    companion object {
        const val ARGUMENT_ADDRESS = "CallBottomSheet_ADDRESS"
        const val ARGUMENT_SDP = "CallBottomSheet_SDP"
        const val ARGUMENT_TYPE = "CallBottomSheet_TYPE"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_call_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val address = arguments?.getParcelable<Address>(ARGUMENT_ADDRESS) ?: return dismiss()
        val sdp = arguments?.getStringArray(ARGUMENT_SDP) ?: return dismiss()
        val type = arguments?.getString(ARGUMENT_TYPE) ?: return dismiss()
        val recipient = Recipient.from(requireContext(), address, false)
        profilePictureView.publicKey = address.serialize()
        profilePictureView.glide = GlideApp.with(this)
        profilePictureView.isLarge = true
        profilePictureView.update(recipient, -1)

        nameTextView.text = recipient.name ?: address.serialize()

    }
}