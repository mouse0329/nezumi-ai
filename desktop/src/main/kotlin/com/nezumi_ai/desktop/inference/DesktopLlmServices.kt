package com.nezumi_ai.desktop.inference

/**
 * 設定画面とチャットで同一の [LlamaCppEngine] を共有する。
 * 別インスタンスだと「設定でロードしたモデルがチャットに反映されない」「モックと実推論の状態が食い違う」になる。
 */
object DesktopLlmServices {
    val llamaEngine: LlamaCppEngine = LlamaCppEngine()
}
