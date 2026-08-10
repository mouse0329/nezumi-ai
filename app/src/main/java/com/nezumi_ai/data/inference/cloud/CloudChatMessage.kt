package com.nezumi_ai.data.inference.cloud

import android.graphics.Bitmap

/**
 * プロバイダ非依存の共通中間チャットメッセージ形式。
 *
 * 既存の [com.nezumi_ai.data.inference.AIInferenceEngine.inferenceWithMedia] が
 * 受け取るのは「連結済みの単一プロンプト文字列 + 画像 [Bitmap] リスト」なので、
 * クラウドエンジンでは
 *   1. プロンプト文字列を粗くパースして role ごとに分割し、
 *   2. 最終ユーザーターンに画像を添付する、
 * という素直な変換を行って各社 API のメッセージ配列へ落とし込む。
 *
 * ## Role
 * - [SYSTEM]: システムプロンプト。Claude のように `system` フィールドが独立している
 *             プロバイダでは第 1 メッセージから分離して送る。
 * - [USER]:   ユーザー発話。
 * - [ASSISTANT]: モデル応答（過去ターン）。
 *
 * ## 画像
 * 現状 [images] は最終ユーザーメッセージにのみ添付される想定。
 * 音声は本モジュールのスコープ外のため保持しない。
 */
data class CloudChatMessage(
    val role: Role,
    val text: String,
    val images: List<Bitmap> = emptyList()
) {
    enum class Role { SYSTEM, USER, ASSISTANT }
}

/**
 * 既存エンジンが渡してくる「連結済みプロンプト文字列」を粗く role 別に分解するヘルパ。
 *
 * ネズミ AI 内部のプロンプトビルダは概ね
 *   `<role>system</role>...<role>user</role>...<role>model</role>...`
 * 系ではなく、Gemma/ChatML 系の生テンプレを連結する。既存の
 * [com.nezumi_ai.data.inference.PromptBuilder] が生成する結果は
 * 「単一ユーザー発話 + システム指示」の形になっているケースがほとんどなので、
 * クラウド側では **プロンプト全体を単一 USER メッセージとして送る** のを既定動作とし、
 * それに画像を添付する。
 *
 * この方針の理由:
 * - Gemini/Claude の複数ターン API は role 順を厳格に検証するため、
 *   雑にターン分割すると HTTP 400 の原因になる。
 * - 既存の ChatViewModel は毎リクエスト毎に session ごとの文脈を
 *   まとめた「単一プロンプト」を渡してくる設計なので、そのまま渡すのが安全。
 * - ツールコールは既存のプロンプト注入方式で処理済みのため、
 *   クラウド側で改めて role 分割する意味が薄い。
 *
 * システムプロンプトを分離したいプロバイダ (Claude / OpenAI) では、
 * `system:` プレフィックスで始まる冒頭ブロックだけを軽く抽出できるように、
 * [splitOptionalSystem] のオプショナルなヘルパを用意する。
 */
object CloudPromptSplitter {

    /**
     * プロンプト先頭が「System: ...\n\n」のような形をしていれば
     * (SYSTEM_TEXT, REMAINING) のペアで返す。それ以外は (null, prompt)。
     *
     * ネズミ AI のプロンプトビルダは基本 role タグを使わないため、
     * この関数は現状ほぼ「システム分離なし」で動くが、将来
     * プロンプトビルダが system 分離出力に変わったときに素直に動くように
     * 用意しておく。
     */
    fun splitOptionalSystem(prompt: String): Pair<String?, String> {
        val trimmedStart = prompt.trimStart()
        val markers = listOf("System:\n", "System: ", "SYSTEM:\n", "SYSTEM: ")
        for (marker in markers) {
            if (trimmedStart.startsWith(marker)) {
                val body = trimmedStart.removePrefix(marker)
                // 空行 (\n\n) までを system 部として取り出す
                val idx = body.indexOf("\n\n")
                return if (idx >= 0) {
                    body.substring(0, idx).trim() to body.substring(idx + 2).trimStart()
                } else {
                    body.trim() to ""
                }
            }
        }
        return null to prompt
    }
}
