package com.nezumi_ai.presentation.ui.fragment

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
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
        //   Neutral ボタンとして「コピー」を追加し、押下時にクリップボードへ格納する。
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("エラー")
            .setIcon(com.nezumi_ai.R.drawable.ic_nezumi_ai)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> notifyDismissed() }
            .setNeutralButton("コピー", null)
            .setCancelable(false)
            .create()
        // Neutral ボタンを押してもダイアログを閉じないようにするため、
        // show 後に OnClickListener を差し替える。
        dialog.setOnShowListener {
            val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            neutral?.setOnClickListener {
                val clipboard = requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("error_message", message))
                Toast.makeText(
                    requireContext(),
                    "エラーメッセージをコピーしました",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        return dialog
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
