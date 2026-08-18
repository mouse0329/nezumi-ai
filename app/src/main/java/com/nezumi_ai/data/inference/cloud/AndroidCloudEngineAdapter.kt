package com.nezumi_ai.data.inference.cloud

import android.graphics.Bitmap
import com.nezumi_ai.data.inference.AIInferenceEngine
import com.nezumi_ai.data.inference.CloudInferenceParams
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.cloud.engine.AbstractCloudInferenceEngine
import kotlinx.coroutines.flow.Flow

/** shared の [AbstractCloudInferenceEngine] を app 側 [AIInferenceEngine] に適合させるアダプタ。 */
class AndroidCloudEngineAdapter(private val delegate: AbstractCloudInferenceEngine) : AIInferenceEngine {

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> =
        delegate.loadModel(modelName, config.toCloudParams())

    suspend fun loadModelWithId(modelId: String, modelName: String, config: InferenceConfig): Result<Unit> =
        delegate.loadModelWithId(modelId, modelName, config.toCloudParams())

    override suspend fun inference(sessionId: Long, prompt: String, config: InferenceConfig): Flow<String> =
        delegate.inference(sessionId, prompt, config.normalized().toCloudParams())

    override suspend fun inferenceWithMedia(
        sessionId: Long, prompt: String, images: List<Bitmap>, audioClips: List<ByteArray>, config: InferenceConfig
    ): Flow<String> {
        val jpegImages = images.map { BitmapJpegEncoder.encodeJpeg(it) }
        return delegate.inferenceWithMedia(sessionId, prompt, jpegImages, config.normalized().toCloudParams())
    }

    override suspend fun unloadModel(): Result<Unit> = delegate.unloadModel()
    override suspend fun cancelInference() = delegate.cancelInference()
    override suspend fun isAvailable(): Boolean = delegate.isAvailable()

    private fun InferenceConfig.toCloudParams(): CloudInferenceParams = CloudInferenceParams(
        maxTokens = maxTokens, temperature = temperature, topP = topP,
        customStopTokens = customStopTokens, enableToolCalling = enableToolCalling, contextWindow = contextWindow
    )
}
