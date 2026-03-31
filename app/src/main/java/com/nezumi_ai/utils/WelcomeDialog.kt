package com.nezumi_ai.utils

import android.app.AlertDialog
import android.content.Context
import androidx.navigation.NavController

object WelcomeDialog {
    fun show(context: Context, navController: NavController? = null) {
        val message = """
ようこそ、ネズミAIへ！

📝 使い方：
• セッションタブ：チャット履歴を確認できます
• チャットタブ：AIとの会話ができます
• 設定タブ：モデルダウンロードやトークン設定ができます

⚙️ 初期設定：
1. 設定タブで「HuggingFace トークン認証」を行ってください
2. モデル（E2B または E4B）をダウンロードしてください

✨ その他：
• ライセンス表示でオープンソースライブラリの情報を確認できます

それでは、AI チャットをお楽しみください！
        """.trimIndent()

        val builder = AlertDialog.Builder(context)
            .setTitle("🐭 ネズミAI へようこそ")
            .setMessage(message)
            .setPositiveButton("了解") { dialog, _ ->
                dialog.dismiss()
            }

        // ナビゲーションコントローラが渡された場合、ライセンスボタンを追加
        if (navController != null) {
            builder.setNegativeButton("ライセンスを見る") { dialog, _ ->
                try {
                    navController.navigate(com.nezumi_ai.R.id.licenseFragment)
                    dialog.dismiss()
                } catch (e: Exception) {
                    dialog.dismiss()
                }
            }
        }

        builder.show()
    }
}
