package com.nezumi_ai.presentation.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ContentInfoCompat
import androidx.core.view.OnReceiveContentListener
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat

/**
 * AppCompatEditText に、
 *  - キーボードの「画像/GIF などのリッチメディア挿入」 (InputConnection commitContent)
 *  - システムの「貼り付け」で受け取った画像 URI (OnReceiveContent)
 * を検知して呼び出し側にコールバックするだけの拡張を加えたもの。
 *
 * 変更履歴:
 *  以前は customSelectionActionModeCallback をカスタムし、
 *  「画像を貼り付け」メニュー項目を差し込んで、そこから独自の
 *  Clipboard 走査ロジックを叩いていた。
 *  今回のリファクタで、通常の「貼り付け」からも画像 URI を拾えるように
 *  ContentInfoCompat 経由で受け取る形に変更し、
 *  カスタムメニュー項目 (「クリップボードから貼り付け」) は廃止した。
 *
 *  これにより:
 *   - ユーザーは「長押し → 貼り付け」だけで、テキストも画像 URI も
 *     区別なく EditText にペーストできる。
 *   - onClipboardImagePaste は画像 URI を検知したときにだけ発火する。
 *   - テキストのペーストはこれまで通り AppCompatEditText 標準実装に委ねる。
 */
class ClipboardAwareEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** 画像 URI がペースト/挿入されたときに呼ばれる。 */
    var onClipboardImagePaste: (() -> Unit)? = null

    init {
        // OS の「貼り付け」やドラッグ&ドロップで画像が渡ってきたとき、
        // それを吸い取って onClipboardImagePaste に通知する。
        // テキストなど画像以外のコンテンツはそのままシステム側に流す。
        ViewCompat.setOnReceiveContentListener(
            this,
            arrayOf("image/*"),
            OnReceiveContentListener { _, payload ->
                val split = payload.partition { item -> item.uri != null }
                val imagePart = split.first
                val remaining = split.second
                if (imagePart != null) {
                    try {
                        onClipboardImagePaste?.invoke()
                    } catch (t: Throwable) {
                        Log.e("ClipboardAwareEditText", "Error in paste callback", t)
                    }
                }
                // 画像以外のペイロード (テキスト等) は通常経路に返す。
                remaining
            }
        )
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        EditorInfoCompat.setContentMimeTypes(outAttrs, arrayOf("image/*"))
        val callback = InputConnectionCompat.OnCommitContentListener { inputContentInfo, flags, _ ->
            // Android 7.1+: IME からリッチコンテンツを渡された時
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
                (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0
            ) {
                try { inputContentInfo.requestPermission() } catch (_: Exception) {}
            }
            try {
                onClipboardImagePaste?.invoke()
            } catch (t: Throwable) {
                Log.e("ClipboardAwareEditText", "Error in commitContent callback", t)
            }
            true
        }
        return InputConnectionCompat.createWrapper(ic, outAttrs, callback)
    }
}
