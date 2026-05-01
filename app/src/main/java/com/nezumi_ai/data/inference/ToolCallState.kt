package com.nezumi_ai.data.inference

/**
 * Tool Calling の状態マシン
 *
 * 状態遷移フロー：
 *   Executing → Result → Responding → Done
 *
 * 各状態で UI は異なるフィードバックを表示します。
 */
sealed class ToolCallState {
    /**
     * State: ⏳ ツール実行中
     * @param toolName 実行対象のツール名
     * @param elapsedMs 経過時間（ミリ秒）
     */
    data class Executing(val toolName: String, val elapsedMs: Long) : ToolCallState()

    /**
     * State: ✅ / ❌ 実行結果
     * @param toolName ツール名
     * @param status 結果ステータス ("success", "error", など)
     * @param resultMessage オプション：結果メッセージ
     */
    data class Result(
        val toolName: String,
        val status: String,
        val resultMessage: String? = null
    ) : ToolCallState()

    /**
     * State: ✍️ AI が回答を作成中
     * Tool Call 実行結果を踏まえてAI応答をジェネレート中
     */
    data object Responding : ToolCallState()

    /**
     * State: 完了
     * Tool Call フロー終了
     */
    data object Done : ToolCallState()
}
