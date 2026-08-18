package com.nezumi_ai.data.inference.cloud

import android.graphics.Bitmap
import com.nezumi_ai.data.inference.AIInferenceEngine
import com.nezumi_ai.data.inference.CloudInferenceParams
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.cloud.engine.AbstractCloudInferenceEngine
import kotlinx.coroutines.flow.Flow

/**
 * shared (commonMain) の [AbstractCloudInferenceEngine] を app 側の
 * [AIInferenceEngine] インターフェースに適合させるアダプタ。
 *
 * - Bitmap リストを JPEG バイト列へ変換してから共通エンジンへ渡す
 *   (変換は旧 ImageEncoding.encodeJpegBase64 と同一の compress 呼び出しで、
 *    Base64 化前のバイト列は旧実装と同一)。
 * - InferenceConfig はクラウドエンジンが参照するフィールドだけに絞った
 *   [CloudInferenceParams] へ写す (normalized() の適用は旧実装と同じく先に行う)。
 */
class AndroidCloudEngineAdapter(
    private val delegate: AbstractCloudInferenceEngine
) : AIInferenceEngine {

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> =
        delegate.loadModel(modelName, config.toCloudParams())

    /** ModelManager がモデル個別設定解決のために modelId も渡してくる経路。 */
    suspend fun loadModelWithId(modelId: String, modelName: String, config: InferenceConfig): Result<Unit> =
        delegate.loadModelWithId(modelId, modelName, config.toCloudParams())

    override suspend fun inference(sessionId: Long, prompt: String, config: InferenceConfig): Flow<String> =
        delegate.inference(sessionId, prompt, config.normalized().toCloudParams())

    override suspend fun inferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
        config: InferenceConfig
    ): Flow<String> {
        val jpegImages = images.map { BitmapJpegEncoder.encodeJpeg(it) }
        return delegate.inferenceWithMedia(sessionId, prompt, jpegImages, config.normalized().toCloudParams())
    }

    override suspend fun unloadModel(): Result<Unit> = delegate.unloadModel()

    override suspend fun cancelInference() = delegate.cancelInference()

    override suspend fun isAvailable(): Boolean = delegate.isAvailable()

    private fun InferenceConfig.toCloudParams(): CloudInferenceParams = CloudInferenceParams(
        maxTokens = maxTokens,
        temperature = temperature,
        topP = topP,
        customStopTokens = customStopTokens,
        enableToolCalling = enableToolCalling,
        contextWindow = contextWindow
    )
}
