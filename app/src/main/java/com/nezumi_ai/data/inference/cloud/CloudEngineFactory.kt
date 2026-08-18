package com.nezumi_ai.data.inference.cloud

import android.content.Context
import com.nezumi_ai.data.inference.AIInferenceEngine
import com.nezumi_ai.data.inference.cloud.engine.ClaudeInferenceEngine
import com.nezumi_ai.data.inference.cloud.engine.GeminiInferenceEngine
import com.nezumi_ai.data.inference.cloud.engine.LmStudioInferenceEngine
import com.nezumi_ai.data.inference.cloud.engine.OllamaInferenceEngine
import com.nezumi_ai.data.inference.cloud.engine.OpenAiInferenceEngine
import java.util.concurrent.ConcurrentHashMap

/** modelId から [AIInferenceEngine] を取り出すファクトリ。shared の Ktor エンジンをアダプタで包む。 */
object CloudEngineFactory {

    private val engines = ConcurrentHashMap<CloudApiKeyStore.Provider, AIInferenceEngine>()

    fun get(context: Context, modelId: String): AIInferenceEngine? {
        val parsed = CloudModelId.parse(modelId) ?: return null
        return engines.getOrPut(parsed.provider) { createEngine(context.applicationContext, parsed.provider) }
    }

    fun resolve(context: Context, modelId: String): Pair<AIInferenceEngine, String>? {
        val parsed = CloudModelId.parse(modelId) ?: return null
        val engine = engines.getOrPut(parsed.provider) { createEngine(context.applicationContext, parsed.provider) }
        return engine to parsed.modelName
    }

    private fun createEngine(appContext: Context, provider: CloudApiKeyStore.Provider): AIInferenceEngine {
        val secureStore = CloudStoresHolder.secure(appContext)
        val configProvider = CloudUserModelRegistry.configProvider(appContext)
        val toolExecutor = AndroidCloudToolExecutor(appContext)
        val engine = when (provider) {
            CloudApiKeyStore.Provider.CLAUDE -> ClaudeInferenceEngine(secureStore, configProvider, toolExecutor)
            CloudApiKeyStore.Provider.GEMINI -> GeminiInferenceEngine(secureStore, configProvider, toolExecutor)
            CloudApiKeyStore.Provider.OPENAI -> OpenAiInferenceEngine(secureStore, configProvider, toolExecutor)
            CloudApiKeyStore.Provider.OLLAMA_LOCAL, CloudApiKeyStore.Provider.OLLAMA_REMOTE ->
                OllamaInferenceEngine(secureStore, configProvider, toolExecutor, provider)
            CloudApiKeyStore.Provider.LM_STUDIO -> LmStudioInferenceEngine(secureStore, configProvider, toolExecutor)
        }
        return AndroidCloudEngineAdapter(engine)
    }
}
