package com.nezumi_ai.utils

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nezumi_ai.R
import com.nezumi_ai.presentation.ui.theme.NezumiComposeTheme

/**
 * 前回起動時に発生したクラッシュのスタックトレースをユーザーに提示するモーダル。
 *
 * 表示は Compose / Material3（[ErrorModalDialog] と同じ系統）。
 * MainActivity がまだ XML ホストなので、外側だけ [Dialog] + [ComposeView] で載せる。
 *
 * ボタン:
 *   - 閉じる: モーダルを閉じ、蓄積されたクラッシュログを削除する
 *   - コピー: スタックトレースをクリップボードへコピー（ダイアログは開いたまま）
 *   - 共有  : ACTION_SEND で共有シートを開く（ダイアログは開いたまま）
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
        val dialog = Dialog(context)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.decorView?.setBackgroundColor(Color.TRANSPARENT)

        val shareBody = buildShareText(log)
        val composeView = ComposeView(context).apply {
            val lifecycleOwner = context as? LifecycleOwner
            val savedStateOwner = context as? SavedStateRegistryOwner
            val viewModelStoreOwner = context as? ViewModelStoreOwner

            if (lifecycleOwner != null) setViewTreeLifecycleOwner(lifecycleOwner)
            if (savedStateOwner != null) setViewTreeSavedStateRegistryOwner(savedStateOwner)
            if (viewModelStoreOwner != null) setViewTreeViewModelStoreOwner(viewModelStoreOwner)

            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NezumiComposeTheme {
                    CrashLogDialogContent(
                        log = log,
                        onClose = {
                            CrashReporter.clearAll(context)
                            dialog.dismiss()
                        },
                        onCopy = {
                            copyToClipboard(context, shareBody)
                            Toast.makeText(
                                context,
                                context.getString(R.string.crash_dialog_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onShare = { shareCrashLog(context, shareBody) }
                    )
                }
            }
        }

        dialog.setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        dialog.show()
    }

    @Composable
    fun CrashLogDialogContent(
        log: CrashReporter.CrashLog,
        onClose: () -> Unit,
        onCopy: () -> Unit,
        onShare: () -> Unit
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier
                .padding(16.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, MaterialTheme.colorScheme.error, shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.crash_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.crash_dialog_subtitle, log.formattedTimestamp()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    R.string.crash_dialog_summary,
                    log.exceptionClass.substringAfterLast('.'),
                    log.message
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = buildDisplayText(log),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onShare,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(R.string.crash_dialog_button_share))
                }
                TextButton(
                    onClick = onCopy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(R.string.crash_dialog_button_copy))
                }
                TextButton(
                    onClick = onClose,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(R.string.crash_dialog_button_close))
                }
            }
        }
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
