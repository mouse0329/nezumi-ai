package com.nezumi_ai.data.inference.prompt

/**
 * プロンプト構築の対象エンジン系統 (計画書 2.4)。
 *
 * これまで `ChatViewModel` の `isGgufEngineModel()` 分岐と
 * 「それ以外は全部 LiteRT (Gemma 固定)」という誤った二分法で決まっていた先を、
 * 明示的な 3 系統に分離する。クラウドは LiteRT と同じ構造化経路を
 * 通らず、独自レンダラー (Phase 5) へ流す。
 */
sealed interface EngineTarget {
    /** llama.cpp 系 (ローカル .gguf)。モデル名 / パスから決まる。 */
    data class Gguf(val modelPath: String) : EngineTarget

    /** LiteRT-LM 系 (.litertlm)。構造化ペイロード + thinkingConfig で制御する。 */
    data class LiteRt(val modelName: String) : EngineTarget

    /** クラウド (Claude / Gemini / OpenAI / LM Studio / Ollama)。 */
    data class Cloud(val cloudModelId: String, val resolvedModelName: String) : EngineTarget

    companion object {
        /** GGUF 判定: 拡張子のみを正とする (既存 `isGgufEngineModel` と同一規則)。 */
        fun isGgufModelName(engineModelName: String): Boolean =
            engineModelName.lowercase().endsWith(".gguf")

        /** クラウドモデル ID (`cloud:...`) 判定。 */
        fun isCloudModelId(engineModelName: String): Boolean =
            engineModelName.trim().startsWith("cloud:", ignoreCase = true)
    }
}
