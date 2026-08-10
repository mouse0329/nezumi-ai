package com.nezumi_ai.data.inference.cloud

import com.nezumi_ai.data.preset.PresetConstants

/**
 * クラウドプロバイダ経由のモデルを表現するモデル ID の命名規約。
 *
 * 形式: `cloud:{providerId}:{modelName}`
 *
 * 例:
 * - `cloud:claude:claude-sonnet-4-5`
 * - `cloud:gemini:gemini-2.5-flash`
 * - `cloud:openai:gpt-4o-mini`
 * - `cloud:ollama-local:llama3.2:3b`   ← modelName に ':' が含まれても可
 * - `cloud:ollama-remote:qwen3:235b`   ← プロバイダ ID は旧称 "ollama-remote" のまま (Ollama Cloud を指す)
 * - `cloud:lmstudio:local-model`
 *
 * ## 後方互換
 * 既存の [PresetConstants.MODEL_GEMINI_API] / [PresetConstants.MODEL_CLAUDE_API]
 * (`gemini_api` / `claude_api`) は識別子だけ予約されていた枠。
 * これらを渡された場合は各社の推奨デフォルトモデルへ暗黙にマッピングする。
 *
 * ## パースの注意
 * 3 セグメント目 (`modelName`) は Ollama のように `:` を含みうるため、
 * split(":", limit = 3) で 3 分割し 3 番目を丸ごとモデル名として採用する。
 */
object CloudModelId {

    private const val PREFIX = "cloud:"

    /** 各種文字列 modelId がクラウド系かを判定する。 */
    fun isCloud(modelId: String): Boolean {
        val trimmed = modelId.trim()
        if (trimmed.startsWith(PREFIX)) return true
        // レガシー識別子 (プリセット定数として予約されているもの) も cloud 扱いにする
        return trimmed == PresetConstants.MODEL_CLAUDE_API ||
                trimmed == PresetConstants.MODEL_GEMINI_API
    }

    /** `cloud:provider:model` を組み立てる。 */
    fun build(provider: CloudApiKeyStore.Provider, modelName: String): String {
        val cleanedName = modelName.trim()
        return "$PREFIX${provider.id}:$cleanedName"
    }

    data class Parsed(
        val provider: CloudApiKeyStore.Provider,
        val modelName: String
    )

    /**
     * 文字列 modelId を [Parsed] に分解する。
     * 認識できない場合や、プロバイダ未対応の場合は null。
     */
    fun parse(modelId: String): Parsed? {
        val trimmed = modelId.trim()

        // ── レガシー識別子 → 各社の 2026-08 時点での「呼び出し先が確実に存在する」
        //     汎用モデルへマッピング。ユーザー入力ではないので、あくまで
        //     「識別子だけ存在するプリセットが暴発しないための保険」。
        when (trimmed) {
            PresetConstants.MODEL_CLAUDE_API ->
                return Parsed(CloudApiKeyStore.Provider.CLAUDE, "claude-3-5-haiku-latest")
            PresetConstants.MODEL_GEMINI_API ->
                return Parsed(CloudApiKeyStore.Provider.GEMINI, "gemini-2.5-flash")
        }

        if (!trimmed.startsWith(PREFIX)) return null
        val body = trimmed.removePrefix(PREFIX)
        val parts = body.split(":", limit = 3)
        if (parts.size < 2) return null

        // 2 セグメント: `cloud:provider` (モデル名なし) は不正。
        // 3 セグメント: `cloud:provider:model` — model は ':' を含んでよい。
        val providerId = parts[0]
        val modelName = when (parts.size) {
            2 -> parts[1]
            else -> parts.drop(1).joinToString(":")
        }
        if (modelName.isBlank()) return null

        val provider = CloudApiKeyStore.Provider.fromId(providerId) ?: return null
        return Parsed(provider, modelName)
    }

    /** 一覧・ラベル表示用の短縮ラベルを返す。 */
    fun displayLabel(modelId: String): String {
        val parsed = parse(modelId) ?: return modelId
        val providerLabel = when (parsed.provider) {
            CloudApiKeyStore.Provider.CLAUDE -> "Claude"
            CloudApiKeyStore.Provider.GEMINI -> "Gemini"
            CloudApiKeyStore.Provider.OPENAI -> "OpenAI"
            CloudApiKeyStore.Provider.OLLAMA_LOCAL -> "Ollama (Local)"
            CloudApiKeyStore.Provider.OLLAMA_REMOTE -> "Ollama (Cloud)"
            CloudApiKeyStore.Provider.LM_STUDIO -> "LM Studio"
        }
        return "$providerLabel · ${parsed.modelName}"
    }
}
