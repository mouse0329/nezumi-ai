package com.nezumi_ai.utils

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.Toast
import com.nezumi_ai.R
import com.nezumi_ai.databinding.DialogCrashLogBinding

/**
 * 前回起動時に発生したクラッシュのスタックトレースをユーザーに提示するモーダル。
 *
 * WelcomeDialog と同系統のパターン (ViewBinding + AlertDialog.Builder + シアン系ボタン色)
 * に揃えている。ボタンは 3 種類:
 *   - 閉じる (POSITIVE): モーダルを閉じ、蓄積されたクラッシュログを削除する
 *   - コピー (NEUTRAL) : スタックトレースをクリップボードへコピー
 *   - 共有   (NEGATIVE): ACTION_SEND で共有シートを開く (メール/Slack 等)
 *
 * 「閉じる」を押した時点でクラッシュログは削除される。
 *   → 再表示ループを防ぎつつ、コピー/共有だけしたい場合はダイアログを開いたまま
 *     操作すれば OK (Builder の setPositive*/Neutral/Negative* のいずれも
 *     デフォルトではダイアログを dismiss するが、閉じる以外は clearAll しない)。
 */
object CrashLogDialog {

    /**
     * 未読のクラッシュログがあれば表示し、なければ何もしない。
     * @return モーダルを表示した場合 true。
     */
    fun showIfPending(context: Context): Boolean {
        val log = CrashReporter.getPendingCrash(context) ?: return false
        show(context, log)
        return true
    }

    fun show(context: Context, log: CrashReporter.CrashLog) {
        val binding = DialogCrashLogBinding.inflate(LayoutInflater.from(context))

        binding.crashSubtitleText.text =
            context.getString(R.string.crash_dialog_subtitle, log.formattedTimestamp())
        binding.crashSummaryText.text = context.getString(
            R.string.crash_dialog_summary,
            log.exceptionClass.substringAfterLast('.'),
            log.message
        )
        binding.crashStackText.text = buildDisplayText(log)

        val shareBody = buildShareText(log)

        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .setPositiveButton(R.string.crash_dialog_button_close) { d, _ ->
                // 「閉じる」で初めて蓄積ログを消す。次回起動時の再表示ループを防ぐ。
                CrashReporter.clearAll(context)
                d.dismiss()
            }
            .setNeutralButton(R.string.crash_dialog_button_copy) { _, _ ->
                copyToClipboard(context, shareBody)
                Toast.makeText(
                    context,
                    context.getString(R.string.crash_dialog_copied),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.crash_dialog_button_share) { _, _ ->
                shareCrashLog(context, shareBody)
            }
            .create()

        // 表示前にキャンセル動作を無効化して、誤タップで消えるのを防ぐ
        //   (ボタンからのみ dismiss させる)。
        dialog.setCanceledOnTouchOutside(false)

        dialog.show()

        // WelcomeDialog と揃えてボタン色を水色に。
        val cyanColor = Color.parseColor("#4DD0E1")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(cyanColor)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(cyanColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cyanColor)
    }

    /** ダイアログ本体に表示するテキスト。スタック + logcat 末尾を可読な形で並べる。 */
    private fun buildDisplayText(log: CrashReporter.CrashLog): String = buildString {
        append(log.stackTrace.trimEnd())
        if (log.logcatTail.isNotBlank()) {
            append("\n\n---- logcat ----\n")
            append(log.logcatTail.trimEnd())
        }
    }

    /** コピー/共有用テキスト。デバイス情報などのヘッダも軽く付ける。 */
    private fun buildShareText(log: CrashReporter.CrashLog): String = buildString {
        append("Nezumi AI crash report\n")
        append("time: ").append(log.formattedTimestamp()).append('\n')
        append("thread: ").append(log.threadName).append('\n')
        append("exception: ").append(log.exceptionClass).append('\n')
        append("message: ").append(log.message).append('\n')
        append("device: ")
            .append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL)
            .append(" (Android ").append(android.os.Build.VERSION.RELEASE).append(")\n")
        append("\n---- stack ----\n")
        append(log.stackTrace.trimEnd())
        if (log.logcatTail.isNotBlank()) {
            append("\n\n---- logcat ----\n")
            append(log.logcatTail.trimEnd())
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        cm.setPrimaryClip(
            ClipData.newPlainText(
                context.getString(R.string.crash_dialog_clipboard_label),
                text
            )
        )
    }

    private fun shareCrashLog(context: Context, text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Nezumi AI crash log")
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(
            sendIntent,
            context.getString(R.string.crash_dialog_share_chooser)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }
}
