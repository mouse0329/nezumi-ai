package com.nezumi_ai.presentation.ui.component

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.setPadding
import com.nezumi_ai.R
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.utils.PreferencesHelper
import com.nezumi_ai.presentation.ui.widget.ZoomableImageView
import java.io.File
import java.io.OutputStream

/**
 * 統一メディアビュワー。
 * - 画像 (0..N) : 上部 ZoomableImageView + 下部サムネイル横スクロールで切り替え
 * - 動画 (0..1) : 画像領域の上に VideoView (MediaController) を重ねる。動画があれば
 *                 画像領域の代わりに再生され、下部サムネイル横スクロールから切り替え可能
 * - 音声 (0..1) : 常時下部に SeekBar 付き簡易プレイヤー
 *
 * 送信前 / 送信後 で同じダイアログを使う。
 */
object MediaViewerDialog {
    private const val TAG = "MediaViewerDialog"

    /** 画像 (String URI), 動画 (String URI), 音声 (String URI) を一まとめに扱う */
    data class MediaBundle(
        val imageUris: List<String> = emptyList(),
        val videoUri: String? = null,
        val audioUri: String? = null,
        val title: String = "メディアプレビュー",
        val initialIndex: Int = 0
    ) {
        fun isEmpty(): Boolean = imageUris.isEmpty() && videoUri.isNullOrBlank() && audioUri.isNullOrBlank()
    }

    /**
     * 旧 ImageViewerDialog.show(context, uri) 互換のショートカット。
     * 単一画像だけを開きたい呼び出しから利用する。
     */
    @JvmStatic
    fun show(context: Context, imageUri: String) {
        show(context, MediaBundle(imageUris = listOf(imageUri)))
    }

    @JvmStatic
    fun show(context: Context, bundle: MediaBundle) {
        if (bundle.isEmpty()) return

        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.rgb(6, 9, 14)))
            setCancelable(true)
            if (PreferencesHelper.isDisableScreenshot(context)) {
                window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.rgb(6, 9, 14))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 縦: [トップバー] [中央: 画像 or 動画] [音声プレイヤー] [サムネ横スクロール] [ボトムバー]
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(column)

        // --- Top bar ---
        column.addView(buildTopBar(context, bundle.title, onClose = { dialog.dismiss() }))

        // --- Center stage (画像 or 動画) ---
        val stage = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                setMargins(0, dp(context, 4), 0, dp(context, 4))
            }
        }
        val imageView = ZoomableImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        val videoView = VideoView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            visibility = View.GONE
        }
        stage.addView(imageView)
        stage.addView(videoView)
        column.addView(stage)

        // 現在選択中の「stage 表示対象」を管理
        // "video" or "image:<index>"
        val stageKeys = mutableListOf<String>().apply {
            if (!bundle.videoUri.isNullOrBlank()) add("video")
            bundle.imageUris.forEachIndexed { i, _ -> add("image:$i") }
        }
        val initialKey = when {
            !bundle.videoUri.isNullOrBlank() && bundle.initialIndex <= 0 -> "video"
            bundle.imageUris.isNotEmpty() -> "image:${bundle.initialIndex.coerceIn(0, bundle.imageUris.lastIndex)}"
            !bundle.videoUri.isNullOrBlank() -> "video"
            else -> null
        }

        var mediaController: MediaController? = null
        fun showKey(key: String) {
            when {
                key == "video" && !bundle.videoUri.isNullOrBlank() -> {
                    imageView.visibility = View.GONE
                    videoView.visibility = View.VISIBLE
                    val vUri = MessageMediaStore.toUri(bundle.videoUri) ?: bundle.videoUri.toUri()
                    videoView.setVideoURI(vUri)
                    if (mediaController == null) {
                        mediaController = MediaController(context).apply { setAnchorView(videoView) }
                        videoView.setMediaController(mediaController)
                    }
                    videoView.setOnPreparedListener { it.isLooping = false }
                    videoView.requestFocus()
                    videoView.start()
                }
                key.startsWith("image:") -> {
                    val idx = key.removePrefix("image:").toIntOrNull() ?: 0
                    val uriStr = bundle.imageUris.getOrNull(idx) ?: return
                    if (videoView.isPlaying) videoView.pause()
                    videoView.visibility = View.GONE
                    imageView.visibility = View.VISIBLE
                    val u = MessageMediaStore.toUri(uriStr) ?: uriStr.toUri()
                    imageView.setImageURI(u)
                }
            }
        }

        // --- 音声プレイヤー (常設、あれば) ---
        val audioPlayerState = if (!bundle.audioUri.isNullOrBlank()) {
            val player = buildAudioPlayer(context, bundle.audioUri)
            column.addView(player.view)
            player
        } else null

        // --- サムネイル横スクロール (画像 + 動画が合わせて2件以上あるときのみ) ---
        if (stageKeys.size >= 2) {
            column.addView(
                buildThumbnailStrip(
                    context,
                    bundle = bundle,
                    stageKeys = stageKeys,
                    initialSelected = initialKey,
                    onSelect = { key -> showKey(key) }
                )
            )
        }

        // --- Bottom action bar ---
        column.addView(
            buildBottomBar(
                context,
                bundle,
                dialog = dialog
            )
        )

        // 初期表示
        initialKey?.let { showKey(it) }

        dialog.setOnDismissListener {
            try {
                if (videoView.isPlaying) videoView.stopPlayback()
            } catch (_: Throwable) {}
            audioPlayerState?.release()
        }

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
    }

    // ---------------------------------------------------------------------
    // Sub-views
    // ---------------------------------------------------------------------

    private fun buildTopBar(context: Context, title: String, onClose: () -> Unit): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 24), dp(context, 16), dp(context, 10))
            background = ColorDrawable(Color.argb(210, 12, 16, 24))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 84)
            )
        }
        bar.addView(
            TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
        )
        bar.addView(actionText(context, "閉じる", onClose))
        return bar
    }

    private class AudioPlayerState(
        val view: View,
        private val player: MediaPlayer,
        private val handler: Handler,
        private val ticker: Runnable
    ) {
        fun release() {
            try { handler.removeCallbacks(ticker) } catch (_: Throwable) {}
            try { player.stop() } catch (_: Throwable) {}
            try { player.release() } catch (_: Throwable) {}
        }
    }

    private fun buildAudioPlayer(context: Context, audioUri: String): AudioPlayerState {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            background = ColorDrawable(Color.argb(200, 20, 26, 36))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val playBtn = TextView(context).apply {
            text = "▶"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
            background = roundedBackground(Color.argb(235, 32, 38, 48), dp(context, 20).toFloat())
            isClickable = true
            isFocusable = true
        }
        val timeLabel = TextView(context).apply {
            text = "0:00 / 0:00"
            setTextColor(Color.rgb(200, 210, 220))
            textSize = 12f
            setPadding(dp(context, 10), 0, dp(context, 10), 0)
        }
        val seek = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            max = 1000
        }
        row.addView(playBtn)
        row.addView(seek)
        row.addView(timeLabel)

        val mp = MediaPlayer()
        val handler = Handler(Looper.getMainLooper())
        val ticker = object : Runnable {
            override fun run() {
                try {
                    if (mp.isPlaying) {
                        val dur = mp.duration.coerceAtLeast(1)
                        val pos = mp.currentPosition
                        seek.progress = ((pos.toLong() * 1000L) / dur.toLong()).toInt()
                        timeLabel.text = "${fmt(pos)} / ${fmt(dur)}"
                    }
                } catch (_: Throwable) {}
                handler.postDelayed(this, 250)
            }
        }
        try {
            val u = MessageMediaStore.toUri(audioUri) ?: audioUri.toUri()
            mp.setDataSource(context, u)
            mp.setOnPreparedListener {
                timeLabel.text = "0:00 / ${fmt(it.duration)}"
            }
            mp.setOnCompletionListener {
                playBtn.text = "▶"
                seek.progress = 0
            }
            mp.prepareAsync()
        } catch (t: Throwable) {
            Log.w(TAG, "audio setDataSource failed", t)
        }

        playBtn.setOnClickListener {
            try {
                if (mp.isPlaying) {
                    mp.pause()
                    playBtn.text = "▶"
                } else {
                    mp.start()
                    playBtn.text = "⏸"
                    handler.post(ticker)
                }
            } catch (_: Throwable) {}
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                try {
                    val dur = mp.duration.coerceAtLeast(1)
                    mp.seekTo(((progress.toLong() * dur.toLong()) / 1000L).toInt())
                } catch (_: Throwable) {}
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        return AudioPlayerState(row, mp, handler, ticker)
    }

    private fun buildThumbnailStrip(
        context: Context,
        bundle: MediaBundle,
        stageKeys: List<String>,
        initialSelected: String?,
        onSelect: (String) -> Unit
    ): View {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 82)
            )
            setBackgroundColor(Color.argb(180, 12, 16, 24))
            setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        scroll.addView(row)

        val thumbSize = dp(context, 68)
        val margin = dp(context, 4)
        val cells = mutableMapOf<String, View>()

        stageKeys.forEach { key ->
            val cell = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                    setMargins(margin, margin, margin, margin)
                }
                background = roundedBackground(Color.argb(255, 26, 32, 44), dp(context, 6).toFloat())
                isClickable = true
                isFocusable = true
            }
            when {
                key == "video" && !bundle.videoUri.isNullOrBlank() -> {
                    // 動画サムネは (可能なら) 先頭画像 (フレーム#1) を借りて背景に、上に ▶
                    val bgImageUri = bundle.imageUris.firstOrNull()
                    if (bgImageUri != null) {
                        val iv = ZoomableImageView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            setImageURI(MessageMediaStore.toUri(bgImageUri) ?: bgImageUri.toUri())
                        }
                        cell.addView(iv)
                    }
                    val play = TextView(context).apply {
                        text = "▶"
                        setTextColor(Color.WHITE)
                        textSize = 22f
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                        background = ColorDrawable(Color.argb(90, 0, 0, 0))
                    }
                    cell.addView(play)
                }
                key.startsWith("image:") -> {
                    val idx = key.removePrefix("image:").toIntOrNull() ?: 0
                    val uriStr = bundle.imageUris.getOrNull(idx)
                    if (uriStr != null) {
                        val iv = ZoomableImageView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            setImageURI(MessageMediaStore.toUri(uriStr) ?: uriStr.toUri())
                        }
                        cell.addView(iv)
                    }
                }
            }
            cell.setOnClickListener {
                highlight(cells, key, context)
                onSelect(key)
            }
            cells[key] = cell
            row.addView(cell)
        }

        if (initialSelected != null) highlight(cells, initialSelected, context)

        return scroll
    }

    private fun highlight(cells: Map<String, View>, selected: String, context: Context) {
        cells.forEach { (key, v) ->
            v.background = roundedBackground(
                if (key == selected) Color.rgb(91, 192, 255) else Color.argb(255, 26, 32, 44),
                dp(context, 6).toFloat()
            )
        }
    }

    private fun buildBottomBar(
        context: Context,
        bundle: MediaBundle,
        dialog: Dialog
    ): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 16))
            background = ColorDrawable(Color.argb(220, 12, 16, 24))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 84)
            )
        }
        val shareUri = bundle.videoUri?.takeIf { it.isNotBlank() }
            ?: bundle.imageUris.firstOrNull()
            ?: bundle.audioUri
        if (shareUri != null) {
            bar.addView(
                actionButton(context, "共有") { share(context, shareUri) }
            )
        }
        val imgToSave = bundle.imageUris.firstOrNull()
        if (imgToSave != null) {
            bar.addView(
                actionButton(context, "画像を保存") { saveToPictures(context, imgToSave) }
            )
        }
        return bar
    }

    // ---------------------------------------------------------------------
    // Helpers copied/adapted from the old ImageViewerDialog
    // ---------------------------------------------------------------------

    private fun actionButton(context: Context, label: String, onClick: () -> Unit): TextView =
        actionText(context, label, onClick).apply {
            gravity = Gravity.CENTER
            background = roundedBackground(Color.argb(235, 32, 38, 48), dp(context, 14).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 48), 1f).apply {
                setMargins(dp(context, 6), 0, dp(context, 6), 0)
            }
        }

    private fun actionText(context: Context, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(Color.rgb(91, 192, 255))
            textSize = 15f
            setPadding(dp(context, 14))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun roundedBackground(color: Int, radius: Float): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun share(context: Context, uriString: String) {
        try {
            val uri = MessageMediaStore.toUri(uriString) ?: uriString.toUri()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "共有"))
        } catch (t: Throwable) {
            Log.w(TAG, "share failed", t)
        }
    }

    private fun saveToPictures(context: Context, imageUriString: String) {
        try {
            val uri = MessageMediaStore.toUri(imageUriString) ?: imageUriString.toUri()
            val name = "nezumi_ai_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NezumiAI")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val outUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(outUri)?.use { out ->
                    resolver.openInputStream(uri)?.use { input -> input.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "NezumiAI")
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out: OutputStream -> input.copyTo(out) }
                }
                // MediaScanner に通知
                val scan = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                scan.data = Uri.fromFile(target)
                context.sendBroadcast(scan)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "saveToPictures failed", t)
        }
    }

    private fun fmt(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "${s / 60}:${"%02d".format(s % 60)}"
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
