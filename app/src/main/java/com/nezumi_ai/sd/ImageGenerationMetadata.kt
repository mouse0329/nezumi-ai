package com.nezumi_ai.sd

/**
 * 画像生成パラメータのメタデータ
 */
data class ImageGenerationMetadata(
    val modelPath: String,        // 使用したモデルのパス
    val modelName: String,        // モデルの簡潔な名前 (e.g., "SD1.5-MNN")
    val prompt: String,           // ポジティブプロンプト
    val negativePrompt: String,   // ネガティブプロンプト
    val steps: Int,               // 生成ステップ数
    val cfg: Float,               // CFGスケール
    val seed: Long,               // 乱数シード
    val scheduler: String,        // スケジューラ識別子 (e.g. dpm, ddim)
    val width: Int,               // 生成画像の幅
    val height: Int,              // 生成画像の高さ
    val backend: String,          // バックエンド ("mnn", "opencl")
    val timestamp: Long,          // 生成時刻
    val generationTimeMs: Long    // 生成にかかった時間（ミリ秒）
)

/**
 * 生成キュー用のアイテム
 */
data class GenerationQueueItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val count: Int = 1,                      // 何番目の生成か (1-10)
    val prompt: String,
    val negativePrompt: String,
    val steps: Int,
    val cfg: Float,
    val seed: Long,                          // -1でランダム
    val scheduler: String = SdScheduler.DEFAULT.id,
    val metadata: ImageGenerationMetadata? = null,
    val status: GenerationStatus = GenerationStatus.PENDING,
    val resultUri: String? = null,           // 生成結果の保存URI
    val errorMessage: String? = null
) {
    enum class GenerationStatus {
        PENDING,      // 実行待ち
        RUNNING,      // 実行中
        COMPLETED,    // 完了
        FAILED,       // 失敗（モデルエラーやメモリ不足など）
        BLOCKED,      // セーフティガードによるブロック（onnx nsfw 判定で止められた）
        CANCELLED     // キャンセル
    }
}

/**
 * 生成キューの管理
 */
data class GenerationQueue(
    val items: List<GenerationQueueItem> = emptyList(),
    val currentIndex: Int = 0,               // 現在実行中のインデックス
    val isRunning: Boolean = false
) {
    val currentItem: GenerationQueueItem?
        get() = items.getOrNull(currentIndex)

    val completedCount: Int
        get() = items.count { it.status == GenerationQueueItem.GenerationStatus.COMPLETED }

    val failedCount: Int
        get() = items.count { it.status == GenerationQueueItem.GenerationStatus.FAILED }

    val blockedCount: Int
        get() = items.count { it.status == GenerationQueueItem.GenerationStatus.BLOCKED }

    fun isComplete(): Boolean = currentIndex >= items.size
}
