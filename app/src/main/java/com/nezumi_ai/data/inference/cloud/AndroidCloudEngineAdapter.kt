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
        notifyCloudInferenceUsed()
        return delegate.inference(sessionId, prompt, config.normalized().toCloudParams())
    }

    override suspend fun inferenceWithMedia(
        sessionId: Long, prompt: String, images: List<Bitmap>, audioClips: List<ByteArray>, config: InferenceConfig
    ): Flow<String> {
        notifyCloudInferenceUsed()
        val jpegImages = images.map { BitmapJpegEncoder.encodeJpeg(it) }
        return delegate.inferenceWithMedia(sessionId, prompt, jpegImages, config.normalized().toCloudParams())
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
