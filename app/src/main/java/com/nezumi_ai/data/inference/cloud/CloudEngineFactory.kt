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

/**
 * ユーザー定義クラウドモデル (プロバイダ + モデル名) を永続化するリポジトリ。
 *
 * UI 側で「クラウドモデルを追加」した結果を [PresetModelCatalog] の
 * `downloadedModels()` と同じ配列に流し込むための保管庫。SharedPreferences に
 * 平文で保存して構わない (機密性のあるのは API キーだけで、モデル名自体は機密ではない)。
 *
 * ## 保存形式
 * `SharedPreferences "cloud_user_models"` のキー `"models"` に
 * 改行区切りで modelId (`cloud:{provider}:{modelName}`) を並べる。
 */
object CloudUserModelRegistry {

    private const val PREFS = "cloud_user_models"
    private const val KEY = "models"

    fun list(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun add(context: Context, modelId: String) {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) return
        val current = list(context).toMutableList()
        if (current.contains(trimmed)) return
        current += trimmed
        save(context, current)
    }

    fun remove(context: Context, modelId: String) {
        val trimmed = modelId.trim()
        val current = list(context).toMutableList()
        if (!current.remove(trimmed)) return
        save(context, current)
    }

    private fun save(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, list.joinToString("\n"))
            .apply()
    }
}
