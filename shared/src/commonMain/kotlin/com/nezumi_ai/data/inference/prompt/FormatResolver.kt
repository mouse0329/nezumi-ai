package com.nezumi_ai.data.inference.prompt

/**
 * 「どの書式を使うか」だけを決める層 (計画書 2.2)。文字列は一切組まない。
 *
 * 解決結果。GGUF のみモデル名推定が必要で、LiteRT-LM / クラウドは
 * エンジン系統だけで構造が一意に決まる (モデル名判定不要 — 計画書 2.2)。
 */
sealed interface ResolvedPromptFormat {
    /** GGUF: 手組みフォールバック形式 (推定結果)。 */
    data class Gguf(val format: PromptFormat) : ResolvedPromptFormat

    /** LiteRT-LM: Conversation API の構造化ペイロード (system / history / current)。 */
    data object LiteRtStructured : ResolvedPromptFormat

    /** クラウド: role 付きメッセージ配列 (CloudChatMessage)。 */
    data object CloudRoleMessages : ResolvedPromptFormat
}

object FormatResolver {

    /**
     * EngineTarget から書式を解決する。
     *
     * GGUF の優先順位 (計画書 2.2):
     *   1. ユーザー指定テンプレート (MODE_AUTO 以外) → ネイティブ minja レンダリング
     *   2. GGUF ファイル内蔵の chat_template        → ネイティブ minja レンダリング
     *   3. どちらも使えない                          → ここで解決する推定フォールバック
     *
     * 1 / 2 はネイティブ (llama.cpp minja) でしかレンダリングできないため、
     * 呼び出し側 (Phase 6 の ChatViewModel) が先にネイティブ経路を試し、
     * 失敗した場合のみこの関数の結果を `GgufRenderer` に渡す。
     *
     * @param hasExplicitUserTemplate ユーザーが MODE_AUTO 以外を選択済みか。
     *        Bug fix(#42): true の場合は GPT-2 でも PlainCompletion を強制しない。
     * @param isGpt2ArchitectureHint GGUF メタデータ上の architecture が gpt2 の場合 true
     *        (ファイル I/O は app 側で行い、結果だけを渡す)。
     */
    fun resolve(
        target: EngineTarget,
        hasExplicitUserTemplate: Boolean = false,
        isGpt2ArchitectureHint: Boolean = false,
    ): ResolvedPromptFormat = when (target) {
        is EngineTarget.Gguf -> ResolvedPromptFormat.Gguf(
            ModelNameHeuristics.guessGgufFormat(
                modelPathOrName = target.modelPath,
                hasExplicitUserTemplate = hasExplicitUserTemplate,
                isGpt2ArchitectureHint = isGpt2ArchitectureHint,
            )
        )
        // LiteRT-LM とクラウドにはモデル名推定を適用しない (計画書 2.2)。
        // これが「クラウドモデルが Gemma 固定の buildForLiteRt に誤って流れる」
        // バグ (計画書 1.2) の構造的な解消点。
        is EngineTarget.LiteRt -> ResolvedPromptFormat.LiteRtStructured
        is EngineTarget.Cloud -> ResolvedPromptFormat.CloudRoleMessages
    }
}
