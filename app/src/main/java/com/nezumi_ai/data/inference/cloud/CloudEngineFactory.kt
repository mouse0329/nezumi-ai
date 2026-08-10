package com.nezumi_ai.data.inference.cloud

import android.content.Context
import com.nezumi_ai.data.inference.AIInferenceEngine
import com.nezumi_ai.data.inference.cloud.engine.ClaudeInferenceEngine
import com.nezumi_ai.data.inference.cloud.engine.GeminiInferenceEngine
import com.nezumi_ai.data.inference.cloud.engine.LmStudioInferenceEngine
import com.nezumi_ai.data.inference.cloud.engine.OllamaInferenceEngine
import com.nezumi_ai.data.inference.cloud.engine.OpenAiInferenceEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * modelId ([CloudModelId] 形式) から対応する [AIInferenceEngine] 実装を取り出すファクトリ。
 *
 * プロバイダ単位で 1 インスタンスをキャッシュする。OkHttpClient や
 * EncryptedSharedPreferences のオープン/クローズを毎回繰り返す意味は無いので、
 * ライフタイムは Application と一致させる。
 *
 * ModelManager からは
 *   `CloudEngineFactory.get(context, modelName)` を呼ぶだけでよい。
 * ModelManager は返ってきたエンジンに対して既存の loadModel/unloadModel/inference を
 * そのまま呼ぶことができる。
 */
object CloudEngineFactory {

    private val engines = ConcurrentHashMap<CloudApiKeyStore.Provider, AIInferenceEngine>()

    /**
     * modelId (`cloud:{provider}:{model}` or レガシー `gemini_api`/`claude_api`) から
     * エンジンを取得する。プロバイダを解決できなければ null。
     *
     * ModelManager 側はこの関数の戻り値を用いて loadModel(modelName, config) を呼ぶ。
     * 呼び出し前に **modelId のパース結果に含まれる pure な modelName を使う**必要が
     * ある点に注意 (エンジン側は "gemini-2.5-flash" 等を期待し、
     * "cloud:gemini:gemini-2.5-flash" を渡してはいけない)。
     */
    fun get(context: Context, modelId: String): AIInferenceEngine? {
        val parsed = CloudModelId.parse(modelId) ?: return null
        return engines.getOrPut(parsed.provider) {
            createEngine(context.applicationContext, parsed.provider)
        }
    }

    /**
     * [CloudModelId.parse] を再エクスポート。ModelManager が「エンジン + 生の modelName」の
     * 両方を一度に取りたいときのためのショートカット。
     */
    fun resolve(context: Context, modelId: String): Pair<AIInferenceEngine, String>? {
        val parsed = CloudModelId.parse(modelId) ?: return null
        val engine = engines.getOrPut(parsed.provider) {
            createEngine(context.applicationContext, parsed.provider)
        }
        return engine to parsed.modelName
    }

    private fun createEngine(
        appContext: Context,
        provider: CloudApiKeyStore.Provider
    ): AIInferenceEngine = when (provider) {
        CloudApiKeyStore.Provider.CLAUDE -> ClaudeInferenceEngine(appContext)
        CloudApiKeyStore.Provider.GEMINI -> GeminiInferenceEngine(appContext)
        CloudApiKeyStore.Provider.OPENAI -> OpenAiInferenceEngine(appContext)
        CloudApiKeyStore.Provider.OLLAMA_LOCAL,
        CloudApiKeyStore.Provider.OLLAMA_REMOTE -> OllamaInferenceEngine(appContext, provider)
        CloudApiKeyStore.Provider.LM_STUDIO -> LmStudioInferenceEngine(appContext)
    }
}

