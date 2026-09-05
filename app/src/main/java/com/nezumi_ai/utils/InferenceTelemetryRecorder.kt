package com.nezumi_ai.utils

import android.content.Context
import android.util.Log
import com.nezumi_ai.data.inference.InferenceConfig
import io.sentry.SentryLevel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全推論エンジンのロード・推論パフォーマンスを記録するレコーダー。
 *
 * [CrashReporter] と同じ「まずローカルに保存する」方針を踏襲する:
 *   - オンデバイス推論の計測データは常に収集し、filesDir 配下に保存する
 *     （設定画面の「ログ」タブなどから後で閲覧できるようにするため）。
 *   - これらのデータを外部（Sentry）へ送るかどうかは、あくまで
 *     [TelemetryGate] のポリシー（クラウド or オンデバイスいずれかの推論
 *     利用時 + ユーザー同意時のみ）に従う。つまり、推論機能を一度も使わずに
 *     いる間はローカル保存のみで完結し、一切ネットワークには出ない。
 *   - いずれかの推論が使われて Sentry がアクティブになった後は、以後発生した
 *     ローカル推論のイベントも breadcrumb/message として送信され得る
 *     （ユーザーの推論プロンプトや生成結果そのものは含めない）。
 *
 * Sentry への送信カテゴリ（[TelemetryGate] 経由）:
 *   - [recordLoadFailure] / [recordInferenceFailure] → 診断情報 (DIAGNOSTICS)
 *   - [recordLoadSuccess] / [recordInferenceSpeed] → パフォーマンス (PERFORMANCE)
 * 失敗情報は「アプリ改善のための障害診断」と位置づけ、パフォーマンス計測とは
 * 別カテゴリに分類している。設定画面のカテゴリ説明文言にも同旨を明記している
 * （両者を変更する場合は必ず揃えて更新すること）。
 */
object InferenceTelemetryRecorder {
    private const val TAG = "InferenceTelemetry"
    private const val DIR_NAME = "inference_telemetry"
    private const val FILE_NAME = "events.jsonl"
    private const val MAX_LINES = 500

    /** モデルロードに失敗した場合に呼ぶ。 */
    fun recordLoadFailure(context: Context, engineLabel: String, modelName: String, error: Throwable) {
        val event = JSONObject().apply {
            put("type", "load_failure")
            put("timestamp", System.currentTimeMillis())
            put("engine", engineLabel)
            put("model", modelName)
            put("error_class", error::class.java.name)
            put("error_message", error.message ?: "")
        }
        appendEvent(context, event)
        Log.w(TAG, "Model load failed: engine=$engineLabel model=$modelName error=${error.message}")

        // Sentry がアクティブな場合のみ転送する。非アクティブなら captureMessage は no-op。
        TelemetryGate.captureDiagnostic(
            "on-device model load failed: engine=$engineLabel model=$modelName cause=${error::class.java.simpleName}",
            SentryLevel.WARNING
        )
    }

    /** モデルロード成功時の所要時間と、実際に使った全エンジン設定を記録する。 */
    fun recordLoadSuccess(
        context: Context,
        engineLabel: String,
        modelName: String,
        durationMs: Long,
        config: InferenceConfig
    ) {
        val event = JSONObject().apply {
            put("type", "load_success")
            put("timestamp", System.currentTimeMillis())
            put("engine", engineLabel)
            put("model", modelName)
            put("duration_ms", durationMs)
            put("config", configJson(config))
        }
        appendEvent(context, event)
        Log.i(TAG, "Model loaded: engine=$engineLabel model=$modelName durationMs=$durationMs")
        TelemetryGate.capturePerformance(
            "model loaded: ${event.toString()}",
            SentryLevel.INFO
        )
    }

    /**
     * 推論完了時の速度を記録する。
     * @param tokensGenerated 生成トークン数（不明なら null）
     * @param durationMs 初回トークンまでではなく、推論全体の所要時間
     */
    fun recordInferenceSpeed(
        context: Context,
        engineLabel: String,
        modelName: String,
        durationMs: Long,
        tokensGenerated: Int?,
        config: InferenceConfig
    ) {
        val tokensPerSecond = if (tokensGenerated != null && tokensGenerated > 0 && durationMs > 0) {
            tokensGenerated * 1000.0 / durationMs
        } else null

        val event = JSONObject().apply {
            put("type", "inference_speed")
            put("timestamp", System.currentTimeMillis())
            put("engine", engineLabel)
            put("model", modelName)
            put("duration_ms", durationMs)
            put("tokens_generated", tokensGenerated ?: JSONObject.NULL)
            put("tokens_per_second", tokensPerSecond ?: JSONObject.NULL)
            put("config", configJson(config))
        }
        appendEvent(context, event)
        Log.d(TAG, "Inference finished: engine=$engineLabel model=$modelName durationMs=$durationMs tps=$tokensPerSecond")
        TelemetryGate.capturePerformance(
            "inference performance: ${event.toString()}",
            SentryLevel.INFO
        )
    }

    private fun configJson(config: InferenceConfig): JSONObject = JSONObject().apply {
        put("context_window", config.contextWindow)
        put("context_compression_enabled", config.contextCompressionEnabled)
        put("context_compression_threshold_percent", config.contextCompressionThresholdPercent)
        put("temperature", config.temperature)
        put("max_top_k", config.maxTopK)
        put("max_tokens", config.maxTokens)
        put("top_p", config.topP)
        put("enable_thinking", config.enableThinking)
        put("enable_speculative_decoding", config.enableSpeculativeDecoding)
        put("backend_type", config.backendType)
        put("require_multimodal", config.requireMultimodal)
        put("llama_cpp_threads", config.llamaCppThreads)
        put("llama_cpp_gpu_layers", config.llamaCppGpuLayers)
        put("llama_cpp_gpu_backend", config.llamaCppGpuBackend)
        put("llama_cpp_batch_size", config.llamaCppBatchSize)
        put("llama_cpp_ubatch_size", config.llamaCppUBatchSize)
        put("llama_cpp_kv_unified", config.llamaCppKvUnified)
        put("llama_cpp_n_keep", config.llamaCppNKeep)
        put("llama_cpp_rope_freq_base", config.llamaCppRopeFreqBase)
        put("llama_cpp_rope_freq_scale", config.llamaCppRopeFreqScale)
        put("custom_stop_tokens", JSONArray(config.customStopTokens))
        put("enable_tool_calling", config.enableToolCalling)
        put("mtp_enabled", config.mtpEnabled)
        put("mtp_draft_tokens", config.mtpDraftTokens)
        put("flash_attention_enabled", config.flashAttentionEnabled)
        put("dynamic_batch_size_enabled", config.dynamicBatchSizeEnabled)
        put("prompt_batch_size", config.promptBatchSize)
        put("generation_batch_size", config.generationBatchSize)
        put("kv_cache_optimization_enabled", config.kvCacheOptimizationEnabled)
        put("context_shift_enabled", config.contextShiftEnabled)
    }

    /** 推論自体が例外で失敗した場合に呼ぶ（ロード成功後、生成中のエラー）。 */
    fun recordInferenceFailure(context: Context, engineLabel: String, modelName: String, error: Throwable) {
        val event = JSONObject().apply {
            put("type", "inference_failure")
            put("timestamp", System.currentTimeMillis())
            put("engine", engineLabel)
            put("model", modelName)
            put("error_class", error::class.java.name)
            put("error_message", error.message ?: "")
        }
        appendEvent(context, event)
        Log.w(TAG, "Inference failed: engine=$engineLabel model=$modelName error=${error.message}")

        TelemetryGate.captureDiagnostic(
            "on-device inference failed: engine=$engineLabel model=$modelName cause=${error::class.java.simpleName}",
            SentryLevel.WARNING
        )
    }

    /** 設定画面などから直近イベントを読むための API。新しい順。 */
    fun readRecentEvents(context: Context, maxCount: Int = 100): List<JSONObject> {
        val file = eventsFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines()
                .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .takeLast(maxCount)
                .reversed()
        }.getOrDefault(emptyList())
    }

    fun clearAll(context: Context) {
        runCatching { eventsFile(context).delete() }
    }

    private fun eventsDir(context: Context): File = File(context.filesDir, DIR_NAME)
    private fun eventsFile(context: Context): File = File(eventsDir(context), FILE_NAME)

    private fun appendEvent(context: Context, event: JSONObject) {
        runCatching {
            val dir = eventsDir(context)
            if (!dir.exists()) dir.mkdirs()
            val file = eventsFile(context)
            file.appendText(event.toString() + "\n")
            enforceRetentionLimit(file)
        }.onFailure { Log.w(TAG, "Failed to persist inference telemetry event", it) }
    }

    /** ファイルが肥大化しないよう、末尾 [MAX_LINES] 行だけ残す簡易ローテーション。 */
    private fun enforceRetentionLimit(file: File) {
        val lines = file.readLines()
        if (lines.size <= MAX_LINES) return
        file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
    }
}
