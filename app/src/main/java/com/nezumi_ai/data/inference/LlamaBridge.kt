package com.nezumi_ai.data.inference

import android.util.Log

/**
 * 本家 llama.cpp (vendor/llama.cpp) 直結のネイティブ層への JNI ブリッジ。
 *
 * ライブラリ名: "llama_bridge"
 * rn-llama 中間層 (llama_rn/) を経由せず llama.h / common/chat.h / mtmd.h を直接呼ぶ。
 * 引数構成・戻り値は旧方式 (RnLlamaNative) に揃えてあり、
 * GgufInferenceEngine からの切り替え (フェーズ10) 時の差分を最小化している。
 */
object LlamaBridge {

    private const val TAG = "LlamaBridge"

    @Volatile
    private var libraryLoaded: Boolean = false

    fun isLibraryLoaded(): Boolean = libraryLoaded

    init {
        try {
            System.loadLibrary("llama_bridge")
            libraryLoaded = true
            Log.i(TAG, "llama_bridge loaded")
        } catch (e: UnsatisfiedLinkError) {
            libraryLoaded = false
            Log.e(TAG, "Failed to load llama_bridge", e)
        }
    }

    /** トークンストリーミング用コールバック（旧 RnLlamaNative.TokenCallback と同型） */
    interface TokenCallback {
        fun onToken(token: String)
    }

    // ─── モデルライフサイクル ────────────────────────────────────

    /**
     * モデルをロードしてコンテキストポインタを返す。
     * @param modelPath gguf ファイルの絶対パス
     * @param nCtx コンテキストウィンドウサイズ
     * @param nBatch 論理バッチサイズ
     * @param nUbatch 物理バッチサイズ
     * @param nThreads CPU スレッド数
     * @param nGpuLayers GPU オフロード層数
     * @param useMmap モデルのメモリマップロード
     * @param useMlock モデルの RAM 固定
     * @param ropeFreqBase RoPE base frequency（0 でモデルデフォルト）
     * @param ropeFreqScale RoPE frequency scale（0 でモデルデフォルト）
     * @param mmprojPath マルチモーダルプロジェクションファイル（null でテキストのみ）
     * @param flashAttentionEnabled Flash Attention 有効化
     * @param contextShiftEnabled コンテキスト溢れ時のシフト継続
     * @param seed 乱数シード（-1 でランダム）
     * @param gpuBackend llama.cpp GPU バックエンド (CPU / OPENCL / VULKAN)
     * @return ネイティブコンテキストポインタ（0 = 失敗）
     */
    external fun llamaInit(
        modelPath: String,
        nCtx: Int,
        nBatch: Int,
        nUbatch: Int,
        nThreads: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
        useMlock: Boolean,
        ropeFreqBase: Float,
        ropeFreqScale: Float,
        mmprojPath: String?,
        flashAttentionEnabled: Boolean,
        contextShiftEnabled: Boolean,
        seed: Int,
        gpuBackend: String
    ): Long

    /** コンテキスト・モデル・mtmd・チャットテンプレートを解放する。 */
    external fun llamaFree(ctx: Long)

    /**
     * llamaInit 完了後、実際にロードされたバックエンドを取得する ("CPU" / "OPENCL" / "VULKAN")。
     * リクエストしたバックエンドが端末で利用できずCPUにフォールバックした場合、
     * ここで返る値は要求値ではなく実際に使われた "CPU" になる。
     */
    external fun nativeGetActualGpuBackend(ctx: Long): String

    /** リクエストしたGPUバックエンドが利用できず、CPUへフォールバックしたかどうか。 */
    external fun nativeGpuBackendFallbackOccurred(ctx: Long): Boolean

    /**
     * モデルをロードせずに、指定バックエンド ("OPENCL" / "VULKAN") が実行時に
     * 本当に使えるデバイスを持つかどうかを問い合わせる。
     * 設定画面の選択可否判定は、ファイルの有無ではなくこちらを使うこと。
     */
    external fun nativeProbeGpuBackendAvailable(gpuBackend: String): Boolean

    // ─── トークナイザ ────────────────────────────────────────────

    /** テキストをトークン ID 配列に変換する。失敗時は null。 */
    external fun llamaTokenize(ctx: Long, text: String, addBos: Boolean): IntArray?

    /** トークン ID を文字列に変換する（デトークナイズ）。 */
    external fun llamaTokenToPiece(ctx: Long, token: Int): String

    // ─── KV キャッシュ ───────────────────────────────────────────

    /** KV キャッシュをクリアする。セッション切り替え時に呼ぶ。 */
    external fun llamaClearKvCache(ctx: Long)

    // ─── 低レベル推論プリミティブ ────────────────────────────────

    /** トークン列をデコード（KV キャッシュに追加）する。0 = 成功、負値 = エラー。 */
    external fun llamaDecode(ctx: Long, tokens: IntArray): Int

    /** ネイティブバッチ容量。llamaDecode() に渡せる最大トークン数。 */
    external fun llamaGetBatchCapacity(ctx: Long): Int

    /** 次トークンをサンプリングして返す。 */
    external fun llamaSample(
        ctx: Long,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float
    ): Int

    /** EOS トークン ID を返す。 */
    external fun llamaEosToken(ctx: Long): Int

    // ─── トークンストリーミングコールバック ──────────────────────

    /** 生成トークンのストリーミングコールバックを登録/解除する。 */
    external fun nativeSetTokenCallback(ctx: Long, callback: TokenCallback?)

    // ─── 生成中断 ────────────────────────────────────────────────

    /** 進行中の生成を中断する（ネイティブ側のアトミックフラグを立てる）。 */
    external fun nativeInterrupt(ctx: Long)

    /**
     * 中断フラグをクリアする。
     * 推論開始前に必ず呼ぶこと（旧方式と同じ注意点）。
     */
    external fun nativeClearInterrupt(ctx: Long)

    // ─── 高レベル補完 API ────────────────────────────────────────

    /** プロンプトに対して一括補完を行う（トークナイズ→デコード→サンプリング→停止語判定）。 */
    external fun nativeComplete(
        ctx: Long,
        prompt: String,
        nPredict: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        stopWords: Array<String>?
    ): String

    /** メディア（画像・音声）付きの一括補完。mtmd 未初期化時はテキストのみにフォールバック。 */
    external fun nativeCompleteWithMedia(
        ctx: Long,
        prompt: String,
        nPredict: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        stopWords: Array<String>?,
        mediaPaths: Array<String>?
    ): String

    // ─── チャットテンプレート ────────────────────────────────────

    /** GGUF 埋め込みチャットテンプレートを OpenAI 互換メッセージ JSON に適用する。 */
    external fun nativeApplyGgufChatTemplate(
        ctx: Long,
        messagesJson: String,
        enableThinking: Boolean,
        addGenerationPrompt: Boolean
    ): String

    /** 明示的な Jinja チャットテンプレート（HF chat_template 互換）を適用する。 */
    external fun nativeApplyJinjaChatTemplate(
        ctx: Long,
        messagesJson: String,
        chatTemplate: String,
        enableThinking: Boolean,
        addGenerationPrompt: Boolean
    ): String

    /** GGUF チャットテンプレートが利用可能かどうか。 */
    external fun nativeHasGgufChatTemplate(ctx: Long): Boolean

    /**
     * 生成結果を content / reasoning_content に分離した JSON を返す。
     * キー: "content", "reasoning_content"
     */
    external fun nativeParseGgufChatOutput(ctx: Long, output: String, isPartial: Boolean): String

    // ─── マルチモーダル情報 ──────────────────────────────────────

    /** ロード済み mmproj が画像入力をサポートするか。 */
    external fun nativeIsVisionSupported(ctx: Long): Boolean

    /** ロード済み mmproj が音声入力をサポートするか。 */
    external fun nativeIsAudioSupported(ctx: Long): Boolean

    /** 音声入力に必要なサンプルレート (Hz)。非対応時は -1。 */
    external fun nativeGetAudioSampleRate(ctx: Long): Int

    // ─── TTS (Qwen3-TTS) ───────────────────────────────────────

    /**
     * Qwen3-TTS による音声合成を一括で行う（設定 > デバッグの動作確認用）。
     * チャット用コンテキストとは独立に、バックボーン GGUF と トークナイザ GGUF
     * (mmproj 相当) を都度ロードして合成し、結果を WAV ファイルへ書き出す。
     * 手順は tools/tts (llama-tts) と同じものを JNI 経由で再現している。
     *
     * @param modelPath バックボーン GGUF の絶対パス
     * @param tokenizerPath トークナイザ GGUF (mmproj 相当) の絶対パス
     * @param text 読み上げテキスト
     * @param speakerPath 声色クローン用の参照音声 (wav/mp3/flac)。null/空で既定スピーカー
     * @param outPath 出力 WAV の絶対パス
     * @param nThreads CPU スレッド数
     * @param nPredict 最大フレーム数（0 以下でデフォルト 512）
     * @param seed 乱数シード（負でランダム）
     * @return JSON 文字列。成功: {"ok":true,...} / 失敗: {"ok":false,"error":"..."}
     */
    external fun nativeTtsSynthesize(
        modelPath: String,
        tokenizerPath: String,
        text: String,
        speakerPath: String?,
        outPath: String,
        nThreads: Int,
        nPredict: Int,
        seed: Int
    ): String

    // ─── タイミング統計 ──────────────────────────────────────────

    /**
     * 直近の推論のタイミング統計。
     * 戻り値: [promptMs, promptTokens, decodeMs, decodeTokens]（失敗時 null）
     */
    external fun nativeGetLastTimings(ctx: Long): FloatArray?

    // ─── ユーティリティ ──────────────────────────────────────────

    /** llama.cpp バージョン・システム情報文字列を返す。 */
    external fun llamaVersion(): String

    /** ビルドに含まれている llama.cpp GPU バックエンド (OPENCL / VULKAN)。 */
    external fun nativeCompiledGpuBackends(): Array<String>

    fun compiledGpuBackends(): Set<String> {
        if (!libraryLoaded) return emptySet()
        return nativeCompiledGpuBackends().toSet()
    }
}
