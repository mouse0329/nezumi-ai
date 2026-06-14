package com.nezumi_ai.presentation.ui.fragment

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.nezumi_ai.presentation.viewmodel.ChatViewModel

class ModelErrorDialogFragment : DialogFragment() {
    companion object {
        private const val ARG_MESSAGE = "message"

        fun newInstance(message: String): ModelErrorDialogFragment {
            return ModelErrorDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_MESSAGE, message) }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val message = requireArguments().getString(ARG_MESSAGE) ?: ""
        return AlertDialog.Builder(requireContext())
            .setTitle("エラー")
            .setIcon(com.nezumi_ai.R.drawable.ic_nezumi_ai)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> notifyDismissed() }
            .setCancelable(false)
            .create()
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        notifyDismissed()
    }

    private fun notifyDismissed() {
        try {
            val vm = ViewModelProvider(requireActivity()).get(ChatViewModel::class.java)
            vm.dismissModelErrorDialogMessage()
        } catch (_: Exception) {
            // ignore
        }
    }
}
