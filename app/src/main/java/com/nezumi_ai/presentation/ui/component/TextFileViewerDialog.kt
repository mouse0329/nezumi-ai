package com.nezumi_ai.presentation.ui.component

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.nezumi_ai.R
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.data.media.TextFileAttachmentEncoding
import com.nezumi_ai.utils.PreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * テキストファイル添付用のテキストビュワー。
 *
 * マルチモーダル添付一覧 (送信前の MediaPreviewBar / 送信後のメッセージ内カード) で
 * テキストファイルをタップしたときに開く。ファイル本体 (content:// / file://) を
 * バックグラウンドで読み込み、プレーンテキストとして全画面表示する。
 *
 * ここで表示する内容はモデルへ送った `<txtfile>` タグの body と同じものだが、
 * タグそのものは UI には出さない (ユーザーには「ファイルの中身」だけを見せる)。
 */
object TextFileViewerDialog {
    private const val TAG = "TextFileViewerDialog"

    // プレビュー用の読み込み上限。MessageMediaStore 側でプロンプトに載る分と同じ上限に
    // 揃えておき、「モデルに送った内容」と「ここで見える内容」が乖離しないようにする。
    private const val PREVIEW_MAX_BYTES = 512 * 1024

    fun show(context: Context, entry: TextFileAttachmentEncoding.TextFileEntry) {
        val viewerBg = ContextCompat.getColor(context, R.color.viewer_bg)
        val barBg = ContextCompat.getColor(context, R.color.viewer_bar_bg)
        val textPrimary = ContextCompat.getColor(context, R.color.viewer_text_primary)
        val textSecondary = ContextCompat.getColor(context, R.color.viewer_text_secondary)
        val surface = ContextCompat.getColor(context, R.color.viewer_surface)

        val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(viewerBg))
            setCancelable(true)
            if (PreferencesHelper.isDisableScreenshot(context)) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(viewerBg)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(column)

        // --- Top bar (ファイル名 + 閉じる) ---
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 24), dp(context, 16), dp(context, 10))
            background = ColorDrawable(barBg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 84)
            )
        }
        topBar.addView(
            TextView(context).apply {
                text = entry.name
                setTextColor(textPrimary)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
        )
        topBar.addView(
            TextView(context).apply {
                text = context.getString(R.string.viewer_close)
                setTextColor(textSecondary)
                textSize = 14f
                setPadding(dp(context, 12), dp(context, 6), dp(context, 4), dp(context, 6))
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss() }
            }
        )
        column.addView(topBar)

        // --- 本文 (スクロール可能なテキスト) ---
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { setMargins(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 16)) }
            background = ColorDrawable(surface)
        }
        val bodyText = TextView(context).apply {
            setTextColor(textPrimary)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            text = context.getString(R.string.txtfile_viewer_loading)
        }
        scroll.addView(bodyText)
        column.addView(scroll)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // ファイル本体は IO で読み込む。ダイアログを閉じたら結果は捨てる。
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        scope.launch {
            val text = runCatching { readTextFile(context, entry.uri) }.getOrElse {
                Log.w(TAG, "Failed to read text file: ${entry.uri}", it)
                null
            }
            withContext(Dispatchers.Main) {
                if (!dialog.isShowing) return@withContext
                bodyText.text = text ?: context.getString(R.string.txtfile_viewer_load_failed)
            }
        }
        dialog.setOnDismissListener { job.cancel() }
    }

    private fun readTextFile(context: Context, uriString: String): String? {
        val uri = MessageMediaStore.toUri(uriString) ?: return null
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            val truncated = bytes.size > PREVIEW_MAX_BYTES
            val body = if (truncated) {
                bytes.copyOf(PREVIEW_MAX_BYTES).toString(Charsets.UTF_8)
            } else {
                bytes.toString(Charsets.UTF_8)
            }
            if (truncated) {
                body + "\n" + context.getString(R.string.txtfile_viewer_truncated_suffix)
            } else body
        }
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
