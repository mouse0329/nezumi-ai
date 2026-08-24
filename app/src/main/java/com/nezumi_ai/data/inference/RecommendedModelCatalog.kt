package com.nezumi_ai.data.inference

/**
 * おすすめ（推奨）モデルの拡張可能なカタログ。
 *
 * - 組み込み LiteRT 系（Gemma）と HF 上の GGUF 推奨モデルを同じリストで扱う。
 * - 新しいおすすめモデルを足すときは [entries] に 1 行追加するだけでよい。
 * - UI（セットアップ / モデル管理）は LocalModel 列挙に直接依存せず、このカタログを参照する。
 *
 * エンジンの違いは [engine] で表現する:
 * - [Engine.LITERT] … 既存の .task / .litertlm（LocalModel 経由）
 * - [Engine.GGUF]   … llama.cpp 系。ダウンロード後は imported 扱い
 */
object RecommendedModelCatalog {

    enum class Engine {
        LITERT,
        GGUF
    }

    /**
     * @param id アプリ内で一意の識別子（設定保存・表示用）
     * @param displayName UI 表示名
     * @param shortDescription 1行説明（セットアップ等）
     * @param engine 推論エンジン種別
     * @param estimatedSizeBytes 未取得時の概算サイズ（リソース警告用）
     * @param localModel LiteRT のときのみ対応する LocalModel。GGUF は null
     * @param hfRepo GGUF のとき Hugging Face リポジトリ（owner/name）
     * @param hfFile GGUF のときリポジトリ内ファイル名
     * @param recommended true ならセットアップ・モデル管理のおすすめ欄に出す
     */
    data class Entry(
        val id: String,
        val displayName: String,
        val shortDescription: String,
        val engine: Engine,
        val estimatedSizeBytes: Long,
        val localModel: ModelFileManager.LocalModel? = null,
        val hfRepo: String? = null,
        val hfFile: String? = null,
        val recommended: Boolean = true,
    )

    /**
     * おすすめモデル一覧。
     * ここに追加すればセットアップ画面とモデル管理画面の両方に反映される。
     */
    val entries: List<Entry> = listOf(
        // --- 既存 LiteRT（Gemma 4）。Gemma 3n はレガシーのためおすすめから外す ---
        Entry(
            id = "Gemma4-2B",
            displayName = "Gemma 4 2B",
            shortDescription = "軽量・高速。まずはこれ（LiteRT）",
            engine = Engine.LITERT,
            estimatedSizeBytes = 2_400_000_000L,
            localModel = ModelFileManager.LocalModel.GEMMA4_2B,
        ),
        Entry(
            id = "Gemma4-4B",
            displayName = "Gemma 4 4B",
            shortDescription = "精度寄り。ハイエンド端末向け（LiteRT）",
            engine = Engine.LITERT,
            estimatedSizeBytes = 3_410_000_000L,
            localModel = ModelFileManager.LocalModel.GEMMA4_4B,
        ),

        // --- 新規おすすめ GGUF ---
        Entry(
            id = "Qwen3.5-2B-Q4_K_M",
            displayName = "Qwen3.5 2B (Q4_K_M)",
            shortDescription = "軽量 GGUF。日本語・多言語に強い",
            engine = Engine.GGUF,
            estimatedSizeBytes = 1_600_000_000L,
            hfRepo = "unsloth/Qwen3.5-2B-GGUF",
            hfFile = "Qwen3.5-2B-Q4_K_M.gguf",
        ),
        Entry(
            id = "Qwen3.5-4B-Q4_K_M",
            displayName = "Qwen3.5 4B (Q4_K_M)",
            shortDescription = "バランス型 GGUF。2Bより高精度",
            engine = Engine.GGUF,
            estimatedSizeBytes = 2_740_000_000L,
            hfRepo = "unsloth/Qwen3.5-4B-GGUF",
            hfFile = "Qwen3.5-4B-Q4_K_M.gguf",
        ),
        Entry(
            id = "LFM2.5-2.6B-Q4_K_M",
            displayName = "LFM2.5 2.6B (Q4_K_M)",
            shortDescription = "オンデバイス向けハイブリッド。軽くて速い",
            engine = Engine.GGUF,
            estimatedSizeBytes = 1_700_000_000L,
            hfRepo = "LiquidAI/LFM2.5-2.6B-GGUF",
            hfFile = "LFM2.5-2.6B-Q4_K_M.gguf",
        ),
    )

    fun recommended(): List<Entry> = entries.filter { it.recommended }

    fun findById(id: String): Entry? = entries.firstOrNull { it.id == id }

    fun findByLocalModel(model: ModelFileManager.LocalModel): Entry? =
        entries.firstOrNull { it.localModel == model }
}
