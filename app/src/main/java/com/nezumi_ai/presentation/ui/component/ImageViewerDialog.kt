package com.nezumi_ai.presentation.ui.component

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import com.nezumi_ai.R
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.presentation.ui.widget.ZoomableImageView
import java.io.File
import java.io.InputStream

object ImageViewerDialog {
    fun show(context: Context, imageUri: String) {
        val uri = MessageMediaStore.toUri(imageUri) ?: return
        val dialog = Dialog(context, android.R.style.Theme_Material_NoActionBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // ★ バグ修正: このビューワーがロック画面より前面に出てしまう問題に対応。
            //   FLAG_SHOW_WHEN_LOCKED / FLAG_DISMISS_KEYGUARD / FLAG_TURN_SCREEN_ON は
            //   どのパスでも陳列しないよう、明示的にクリアする。
            window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
            // ★ スクリーンショット無効化設定が ON のときは Dialog 自体に FLAG_SECURE を付ける。
            if (com.nezumi_ai.utils.PreferencesHelper.isDisableScreenshot(context)) {
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

        val imageView = ZoomableImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(0, dp(context, 72), 0, dp(context, 92))
            }
            contentDescription = context.getString(R.string.message_image)
            setImageURI(uri)
        }
        root.addView(imageView)

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 24), dp(context, 16), dp(context, 10))
            background = ColorDrawable(Color.argb(210, 12, 16, 24))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 84),
                Gravity.TOP
            )
        }
        topBar.addView(
            TextView(context).apply {
                text = "画像プレビュー"
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        topBar.addView(actionText(context, "閉じる") { dialog.dismiss() })
        root.addView(topBar)

        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 16))
            background = ColorDrawable(Color.argb(220, 12, 16, 24))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 84),
                Gravity.BOTTOM
            )
        }
        bottomBar.addView(actionButton(context, "共有") { share(context, uri) })
        bottomBar.addView(actionButton(context, "フォルダ保存") { saveToPictures(context, uri) })
        root.addView(bottomBar)

        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

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

    private fun saveToPictures(context: Context, uri: Uri) {
        try {
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
                openInputStream(context, uri)?.use { input ->
                    resolver.openOutputStream(outUri)?.use { output -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
            } else {
                val bitmap = openInputStream(context, uri)?.use { BitmapFactory.decodeStream(it) } ?: return
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.insertImage(context.contentResolver, bitmap, name, "nezumi-ai")
            }
            Toast.makeText(context, "フォルダに保存しました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "保存に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(context: Context, uri: Uri) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "共有"))
    }

    private fun openInputStream(context: Context, uri: Uri): InputStream? {
        return if (uri.scheme == "file") {
            val path = uri.path ?: return null
            File(path).inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
