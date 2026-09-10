package com.nezumi_ai.data.inference.cloud

import android.graphics.Bitmap
import com.nezumi_ai.data.inference.AIInferenceEngine
import com.nezumi_ai.data.inference.CloudInferenceParams
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.cloud.engine.AbstractCloudInferenceEngine
import com.nezumi_ai.utils.TelemetryGate
import kotlinx.coroutines.flow.Flow

/** shared の [AbstractCloudInferenceEngine] を app 側 [AIInferenceEngine] に適合させるアダプタ。 */
class AndroidCloudEngineAdapter(private val delegate: AbstractCloudInferenceEngine) : AIInferenceEngine {

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        notifyCloudInferenceUsed()
        return delegate.loadModel(modelName, config.toCloudParams())
    }

    suspend fun loadModelWithId(modelId: String, modelName: String, config: InferenceConfig): Result<Unit> {
        notifyCloudInferenceUsed()
        return delegate.loadModelWithId(modelId, modelName, config.toCloudParams())
    }

    override suspend fun inference(sessionId: Long, prompt: String, config: InferenceConfig): Flow<String> {
        // Phase 6 完了までの互換経路: 平文プロンプトを単一 USER メッセージに包んで渡す。
        // 新規コードは inference(messages) を使うこと (CloudRenderer 経由)。
        notifyCloudInferenceUsed()
        return delegate.inference(
            sessionId,
            listOf(CloudChatMessage(CloudChatMessage.Role.USER, prompt)),
            config.normalized().toCloudParams()
        )
    }

    override suspend fun inferenceWithMedia(
        sessionId: Long, prompt: String, images: List<Bitmap>, audioClips: List<ByteArray>, config: InferenceConfig
    ): Flow<String> {
        notifyCloudInferenceUsed()
        val jpegImages = images.map { BitmapJpegEncoder.encodeJpeg(it) }
        return delegate.inferenceWithMedia(
            sessionId,
            listOf(CloudChatMessage(CloudChatMessage.Role.USER, prompt)),
            jpegImages,
            config.normalized().toCloudParams()
        )
    }

    /**
     * Phase 5 の正式経路: role 付きメッセージ配列を受け取る。
     * 複数ターン履歴を構造化したままクラウド API に届けるため、新規コードはこちらを使う
     * (プロンプト構築は CloudRenderer 参照)。
     */
    suspend fun inferenceWithMessages(
        sessionId: Long,
        messages: List<CloudChatMessage>,
        config: InferenceConfig
    ): Flow<String> {
        notifyCloudInferenceUsed()
        return delegate.inference(sessionId, messages, config.normalized().toCloudParams())
    }

    suspend fun inferenceWithMessagesAndMedia(
        sessionId: Long,
        messages: List<CloudChatMessage>,
        images: List<Bitmap>,
        config: InferenceConfig
    ): Flow<String> {
        notifyCloudInferenceUsed()
        val jpegImages = images.map { BitmapJpegEncoder.encodeJpeg(it) }
        return delegate.inferenceWithMedia(sessionId, messages, jpegImages, config.normalized().toCloudParams())
    }

    /**
     * クラウド推論エンジンが実際に使われるこのアダプタ経由の呼び出しのたびに、
     * TelemetryGate へ通知する。オンデバイス推論 (GgufInferenceEngine / LiteRtLmEngine)
     * はこのクラスを経由しないため、通知は一切発生しない。
     * 通知自体は同意済みでない限り実質何もしない軽量な呼び出し。
     */
    private fun notifyCloudInferenceUsed() {
        runCatching { TelemetryGate.onCloudInferenceUsed() }
    }

    override suspend fun unloadModel(): Result<Unit> = delegate.unloadModel()
    override suspend fun cancelInference() = delegate.cancelInference()
    override suspend fun isAvailable(): Boolean = delegate.isAvailable()

    private fun InferenceConfig.toCloudParams(): CloudInferenceParams = CloudInferenceParams(
        maxTokens = maxTokens, temperature = temperature, topP = topP,
        customStopTokens = customStopTokens, enableToolCalling = enableToolCalling, contextWindow = contextWindow
    )
}
