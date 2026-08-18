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
 * 実体は shared (commonMain) の Ktor ベースエンジンを [AndroidCloudEngineAdapter] で
 * ラップしたもの。プロバイダ単位で 1 インスタンスをキャッシュし、ライフタイムは
 * Application と一致させる。
 *
 * ModelManager からは `CloudEngineFactory.get(context, modelName)` を呼ぶだけでよい。
 */
object CloudEngineFactory {

    private val engines = ConcurrentHashMap<CloudApiKeyStore.Provider, AIInferenceEngine>()

    /**
     * modelId (`cloud:{provider}:{model}` or レガシー `gemini_api`/`claude_api`) から
     * エンジンを取得する。プロバイダを解決できなければ null。
     * 呼び出し前に **パース結果の pure な modelName を使う** 必要がある点に注意。
     */
    fun get(context: Context, modelId: String): AIInferenceEngine? {
        val parsed = CloudModelId.parse(modelId) ?: return null
        return engines.getOrPut(parsed.provider) {
            createEngine(context.applicationContext, parsed.provider)
        }
    }

    /** [CloudModelId.parse] の再エクスポート + エンジン取得のショートカット。 */
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
    ): AIInferenceEngine {
        val secureStore = CloudStoresHolder.secure(appContext)
        val configProvider = CloudUserModelRegistry.configProvider(appContext)
        val toolExecutor = AndroidCloudToolExecutor(appContext)
        val engine = when (provider) {
            CloudApiKeyStore.Provider.CLAUDE -> ClaudeInferenceEngine(secureStore, configProvider, toolExecutor)
            CloudApiKeyStore.Provider.GEMINI -> GeminiInferenceEngine(secureStore, configProvider, toolExecutor)
            CloudApiKeyStore.Provider.OPENAI -> OpenAiInferenceEngine(secureStore, configProvider, toolExecutor)
            CloudApiKeyStore.Provider.OLLAMA_LOCAL,
            CloudApiKeyStore.Provider.OLLAMA_REMOTE ->
                OllamaInferenceEngine(secureStore, configProvider, toolExecutor, provider)
            CloudApiKeyStore.Provider.LM_STUDIO -> LmStudioInferenceEngine(secureStore, configProvider, toolExecutor)
        }
        return AndroidCloudEngineAdapter(engine)
    }
}
