package com.nezumi_ai.data.inference

import android.util.Log

/**
 * llama.cpp ネイティブ層への JNI ブリッジ。
 *
 * ライブラリ名: "llama_bridge"
 * CMakeLists.txt で add_library(llama_bridge SHARED llama_bridge.cpp) し、
 * llama.cpp の llama.h / ggml.h をインクルードする。
 *
 * ロード順: llama.cpp がビルドする libllama.so → libggml.so → libllama_bridge.so
 * の順に System.loadLibrary() する必要がある場合は companion init を調整すること。
 */
object LlamaBridge {

    private const val TAG = "LlamaBridge"

    @Volatile
    private var libraryLoaded: Boolean = false

    fun isLibraryLoaded(): Boolean = libraryLoaded

    init {
        try {
            // llama.cpp AAR を使う場合はこれだけでよい
            System.loadLibrary("llama_bridge")
            libraryLoaded = true
            Log.i(TAG, "llama_bridge loaded")
        } catch (e: UnsatisfiedLinkError) {
            libraryLoaded = false
            Log.e(TAG, "Failed to load llama_bridge", e)
        }
    }

    // ─── モデルライフサイクル ────────────────────────────────────

    /**
     * モデルをロードしてコンテキストポインタを返す。
     * @param modelPath gguf ファイルの絶対パス
     * @param nCtx コンテキストウィンドウサイズ
     * @param nThreads CPU スレッド数
     * @param nGpuLayers GPU オフロード層数（Vulkan / OpenCL 対応ビルド時のみ有効）
     * @param seed 乱数シード（-1 でランダム）
     * @return ネイティブコンテキストポインタ（0 = 失敗）
     */
    external fun llamaInit(
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
        seed: Int
    ): Long

    /**
     * コンテキスト・モデルを解放する。
     * 必ず llamaInit と対応させること。
     */
    external fun llamaFree(ctx: Long)

    // ─── トークナイザ ────────────────────────────────────────────

    /**
     * テキストをトークン ID 配列に変換する。
     * @return トークン ID 配列（失敗時は null）
     */
    external fun llamaTokenize(ctx: Long, text: String, addBos: Boolean): IntArray?

    /**
     * トークン ID を文字列に変換する（デトークナイズ）。
     */
    external fun llamaTokenToPiece(ctx: Long, token: Int): String

    // ─── KV キャッシュ ───────────────────────────────────────────

    /**
     * KV キャッシュをクリアする。
     * セッション切り替え時に呼ぶ。
     */
    external fun llamaClearKvCache(ctx: Long)

    // ─── 推論ループ ──────────────────────────────────────────────

    /**
     * トークン列をデコード（KV キャッシュに追加）する。
     * @return 0 = 成功、負値 = エラー
     */
    external fun llamaDecode(ctx: Long, tokens: IntArray): Int

    /**
     * ネイティブバッチ容量を返す。
     * llamaDecode() に渡すトークン配列はこの長さ以下である必要がある。
     */
    external fun llamaGetBatchCapacity(ctx: Long): Int

    /**
     * 次トークンをサンプリングして返す。
     * @param temperature 温度
     * @param topP nucleus sampling 閾値
     * @param topK top-k 閾値
     * @param repeatPenalty 繰り返しペナルティ
     * @return サンプリングされたトークン ID（EOS は llamaEosToken() と比較）
     */
    external fun llamaSample(
        ctx: Long,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float
    ): Int

    /**
     * EOS トークン ID を返す。
     */
    external fun llamaEosToken(ctx: Long): Int

    // ─── ユーティリティ ──────────────────────────────────────────

    /**
     * llama.cpp バージョン文字列を返す。
     */
    external fun llamaVersion(): String
}
