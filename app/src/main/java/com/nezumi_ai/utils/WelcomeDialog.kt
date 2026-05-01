package com.nezumi_ai.utils

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.view.LayoutInflater
import androidx.navigation.NavController
import com.nezumi_ai.databinding.DialogWelcomeBinding

object WelcomeDialog {
    fun show(context: Context, navController: NavController? = null) {
        val binding = DialogWelcomeBinding.inflate(LayoutInflater.from(context))
        
        // タイトルにグラデーションを適用
        binding.titleText.viewTreeObserver.addOnGlobalLayoutListener {
            val width = binding.titleText.width.toFloat()
            binding.titleText.paint.shader = LinearGradient(
                0f, 0f, width, 0f,
                intArrayOf(0xFF4DD0E1.toInt(), 0xFF0288D1.toInt()), // シアン → ブルー
                null,
                Shader.TileMode.CLAMP
            )
            binding.titleText.invalidate()
        }
        
        val builder = AlertDialog.Builder(context)
            .setView(binding.root)
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

        val dialog = builder.show()
        
        // ボタンのテキストカラーを水色に設定
        val cyanColor = Color.parseColor("#4DD0E1")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(cyanColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cyanColor)
    }
}
